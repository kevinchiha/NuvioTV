-- KevBox TV — watched_items cloud restore. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. No secrets.
-- Event-sourced: watched_items (state) + watched_items_events (append-only log). Spec §5.2, R1-R8.
-- REQUIRES PostgreSQL 15+ for `unique nulls not distinct` (branch + prod are PG17).

-- 1. Tables -----------------------------------------------------------------------
create table if not exists public.watched_items (
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  content_id   text   not null,
  content_type text   not null,
  title        text   not null default '',
  season       int,
  episode      int,
  watched_at   bigint not null,                       -- epoch ms
  updated_at   timestamptz not null default now(),
  -- R3/R6: NULL-aware composite unique key = the ON CONFLICT upsert target. Movies push
  -- season/episode as SQL NULL; `nulls not distinct` makes two movie rows with the same
  -- content_id collide (the default NULLS DISTINCT would duplicate them forever).
  unique nulls not distinct (user_id, profile_id, content_id, season, episode)
);

create table if not exists public.watched_items_events (
  event_id     bigint generated always as identity primary key,  -- global monotonic cursor
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  operation    text   not null check (operation in ('upsert','delete')),
  content_id   text   not null,
  content_type text   not null default '',
  title        text   not null default '',
  season       int,
  episode      int,
  watched_at   bigint not null default 0,             -- R7: zeroed on delete events
  created_at   timestamptz not null default now()
);
create index if not exists watched_items_events_owner_idx
  on public.watched_items_events (user_id, profile_id, event_id);

-- 2. RLS: read own rows only. Writes go through SECURITY DEFINER RPCs (below).
alter table public.watched_items        enable row level security;
alter table public.watched_items_events enable row level security;
drop policy if exists "read own watched_items" on public.watched_items;
create policy "read own watched_items" on public.watched_items
  for select using (auth.uid() = user_id);
drop policy if exists "read own watched_items_events" on public.watched_items_events;
create policy "read own watched_items_events" on public.watched_items_events
  for select using (auth.uid() = user_id);

-- 2b. Revoke default DML; writes are RPC-only. SELECT stays for authenticated (RLS-scoped); anon none.
revoke insert, update, delete, truncate, references, trigger
  on public.watched_items, public.watched_items_events from anon, authenticated;
revoke select on public.watched_items, public.watched_items_events from anon;
grant  select on public.watched_items, public.watched_items_events to authenticated;

-- 3. Push: explicit-owner inner fn + thin JWT-resolving wrapper. R2 watched_at-guarded upsert;
--    append an event ONLY when the row changed. R4 NULL-owner no-op.
create or replace function public.sync_push_watched_items_for(
  p_owner uuid, p_profile_id int, p_items jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; v_changed boolean;
begin
  if p_owner is null or p_items is null then return; end if;
  for e in select value from jsonb_array_elements(p_items) as t(value) loop
    insert into public.watched_items as wi(
      user_id, profile_id, content_id, content_type, title, season, episode, watched_at)
    values (
      p_owner, p_profile_id, e->>'content_id', e->>'content_type', coalesce(e->>'title',''),
      nullif(e->>'season','')::int, nullif(e->>'episode','')::int, (e->>'watched_at')::bigint)
    on conflict (user_id, profile_id, content_id, season, episode) do update
      set content_type=excluded.content_type, title=excluded.title,
          watched_at=excluded.watched_at, updated_at=now()
      where excluded.watched_at > wi.watched_at                  -- R2 guard ("strictly newer")
    returning true into v_changed;

    if v_changed then                                            -- NULL (no row) => not changed
      insert into public.watched_items_events(
        user_id, profile_id, operation, content_id, content_type, title, season, episode, watched_at)
      values (p_owner, p_profile_id, 'upsert', e->>'content_id', e->>'content_type',
        coalesce(e->>'title',''), nullif(e->>'season','')::int, nullif(e->>'episode','')::int,
        (e->>'watched_at')::bigint);
    end if;
  end loop;
end $$;

create or replace function public.sync_push_watched_items(
  p_items jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_watched_items_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

-- 3b. Function ACLs (inner _for revoked from members; wrapper is the only member-facing entry).
revoke all on function public.sync_push_watched_items_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_watched_items(jsonb, int) from public, anon;
grant  execute on function public.sync_push_watched_items(jsonb, int) to authenticated;

-- 4. Pull snapshot. SECURITY DEFINER + explicit owner predicate (R1). Exact SupabaseWatchedItem
--    shape (R7): user_id::text + the 7 other emitted model keys (id omitted — optional). 1-based
--    paging: offset = (p_page-1)*p_page_size. R8 total order with a unique tiebreaker.
create or replace function public.sync_pull_watched_items(
  p_profile_id int, p_page int, p_page_size int
) returns table(
  user_id text, content_id text, content_type text, title text,
  season int, episode int, watched_at bigint, profile_id int
) language sql security definer set search_path = '' as $$
  select wi.user_id::text, wi.content_id, wi.content_type, wi.title,
         wi.season, wi.episode, wi.watched_at, wi.profile_id
  from public.watched_items wi
  where wi.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and wi.profile_id = p_profile_id
  order by wi.watched_at asc, wi.content_id asc,
           wi.season asc nulls first, wi.episode asc nulls first    -- R8 total order: NULL-safe,
                                                                    -- avoids coalesce(-1) aliasing a real -1
  limit  greatest(p_page_size, 0)
  offset greatest((p_page - 1) * p_page_size, 0)                    -- 1-based page
$$;
revoke all     on function public.sync_pull_watched_items(int, int, int) from public, anon;
grant  execute on function public.sync_pull_watched_items(int, int, int) to authenticated;
