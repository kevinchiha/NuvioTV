-- KevBox TV — watch_progress cloud restore. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. No secrets.
-- Event-sourced: watch_progress (state) + watch_progress_events (append-only log). Spec §5.1, R1-R8.

-- 1. Tables -----------------------------------------------------------------------
create table if not exists public.watch_progress (
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  progress_key text   not null,
  content_id   text   not null,
  content_type text   not null,
  video_id     text   not null default '',
  season       int,
  episode      int,
  position     bigint not null,
  duration     bigint not null,
  last_watched bigint not null,                       -- epoch ms
  updated_at   timestamptz not null default now(),
  primary key (user_id, profile_id, progress_key)     -- R3: upsert target
);

create table if not exists public.watch_progress_events (
  event_id     bigint generated always as identity primary key,  -- global monotonic cursor
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  operation    text   not null check (operation in ('upsert','delete')),
  progress_key text   not null,
  content_id   text   not null default '',
  content_type text   not null default '',
  video_id     text   not null default '',
  season       int,
  episode      int,
  position     bigint not null default 0,             -- R7: non-null even on delete events
  duration     bigint not null default 0,
  last_watched bigint not null default 0,
  created_at   timestamptz not null default now()
);
create index if not exists watch_progress_events_owner_idx
  on public.watch_progress_events (user_id, profile_id, event_id);

-- 2. RLS: read own rows only. Writes go through SECURITY DEFINER RPCs (below).
alter table public.watch_progress        enable row level security;
alter table public.watch_progress_events enable row level security;
drop policy if exists "read own watch_progress" on public.watch_progress;
create policy "read own watch_progress" on public.watch_progress
  for select using (auth.uid() = user_id);
drop policy if exists "read own watch_progress_events" on public.watch_progress_events;
create policy "read own watch_progress_events" on public.watch_progress_events
  for select using (auth.uid() = user_id);

-- 2b. Revoke default DML grants (mirrors member_addon_setup.sql). Writes are RPC-only.
revoke insert, update, delete, truncate, references, trigger
  on public.watch_progress, public.watch_progress_events from anon, authenticated;
-- 2c. SELECT stays for authenticated but is RLS-scoped to own rows (defense-in-depth alongside the
--     owner-scoped pull RPC); anon gets nothing. Matches the member_addon read-own posture and makes
--     the read-own policy testable (Task 9, D).
revoke select on public.watch_progress, public.watch_progress_events from anon;
grant  select on public.watch_progress, public.watch_progress_events to authenticated;

-- 3. Push: explicit-owner inner fn (logic) + thin JWT-resolving wrapper.
--    R2: ON CONFLICT guarded by last_watched; append an event ONLY when the row changed.
--    R4: NULL-owner is a no-op.
create or replace function public.sync_push_watch_progress_for(
  p_owner uuid, p_profile_id int, p_entries jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; v_changed boolean;
begin
  if p_owner is null or p_entries is null then return; end if;
  for e in select value from jsonb_array_elements(p_entries) as t(value) loop
    insert into public.watch_progress as wp(
      user_id, profile_id, progress_key, content_id, content_type, video_id,
      season, episode, position, duration, last_watched)
    values (
      p_owner, p_profile_id, e->>'progress_key', e->>'content_id', e->>'content_type',
      coalesce(e->>'video_id',''),
      nullif(e->>'season','')::int, nullif(e->>'episode','')::int,
      (e->>'position')::bigint, (e->>'duration')::bigint, (e->>'last_watched')::bigint)
    on conflict (user_id, profile_id, progress_key) do update
      set content_id=excluded.content_id, content_type=excluded.content_type,
          video_id=excluded.video_id, season=excluded.season, episode=excluded.episode,
          position=excluded.position, duration=excluded.duration,
          last_watched=excluded.last_watched, updated_at=now()
      where excluded.last_watched > wp.last_watched         -- R2 guard ("strictly newer")
    returning true into v_changed;

    if v_changed then                                        -- NULL (no row) => not changed
      insert into public.watch_progress_events(
        user_id, profile_id, operation, progress_key, content_id, content_type,
        video_id, season, episode, position, duration, last_watched)
      values (p_owner, p_profile_id, 'upsert', e->>'progress_key', e->>'content_id',
        e->>'content_type', coalesce(e->>'video_id',''), nullif(e->>'season','')::int,
        nullif(e->>'episode','')::int, (e->>'position')::bigint, (e->>'duration')::bigint,
        (e->>'last_watched')::bigint);
    end if;
  end loop;
end $$;

create or replace function public.sync_push_watch_progress(
  p_entries jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_watch_progress_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_entries)
$$;

-- 3b. Function ACLs (CRITICAL — mirrors member_telemetry_setup.sql lockdown).
--     Inner _for fn takes the owner as an ARGUMENT → it must NOT be reachable by members
--     (else a member POSTs /rpc/sync_push_watch_progress_for with a victim UUID, forging data).
revoke all on function public.sync_push_watch_progress_for(uuid, int, jsonb) from public, anon, authenticated;
--     Wrapper is the only member-facing entry point.
revoke all     on function public.sync_push_watch_progress(jsonb, int) from public, anon;
grant  execute on function public.sync_push_watch_progress(jsonb, int) to authenticated;

-- 4. Pull snapshot. SECURITY DEFINER + explicit owner predicate (R1) — the client sends no owner id.
--    Explicit RETURNS TABLE matching SupabaseWatchProgress EXACTLY (R7): no updated_at, no extra keys,
--    so decode never hits an unknown column. "position" is a reserved word — quote it in the column
--    list (the output JSON key is still literally "position", matching the Kotlin field). user_id cast
--    to text to match the Kotlin String field. R8: total-order tiebreaker so any limited page is stable.
create or replace function public.sync_pull_watch_progress(
  p_profile_id int, p_since_last_watched bigint default null, p_limit int default null
) returns table(
  user_id text, content_id text, content_type text, video_id text,
  season int, episode int, "position" bigint, duration bigint,
  last_watched bigint, progress_key text, profile_id int
) language sql security definer set search_path = '' as $$
  select wp.user_id::text, wp.content_id, wp.content_type, wp.video_id,
         wp.season, wp.episode, wp.position, wp.duration,
         wp.last_watched, wp.progress_key, wp.profile_id
  from public.watch_progress wp
  where wp.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and wp.profile_id = p_profile_id
    and (p_since_last_watched is null or wp.last_watched >= p_since_last_watched)
  order by wp.last_watched desc, wp.progress_key asc                -- R8 total order
  limit p_limit                                                     -- NULL => all rows
$$;
revoke all     on function public.sync_pull_watch_progress(int, bigint, int) from public, anon;
grant  execute on function public.sync_pull_watch_progress(int, bigint, int) to authenticated;

-- 5. Delta cursor: latest event_id for THIS owner, COALESCE'd to 0 (R5) — the client decodes a
--    non-null Long, and watched-items (plan 2) has no client-side fallback, so a NULL would crash.
create or replace function public.sync_get_watch_progress_delta_cursor(p_profile_id int)
  returns bigint language sql security definer set search_path = '' as $$
  select coalesce(max(event_id), 0)
  from public.watch_progress_events
  where user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and profile_id = p_profile_id
$$;
revoke all     on function public.sync_get_watch_progress_delta_cursor(int) from public, anon;
grant  execute on function public.sync_get_watch_progress_delta_cursor(int) to authenticated;

-- 6. Delta pull: owner-scoped (R1), event_id > cursor, ASC, limited (R8). Exact event shape (R7);
--    "position" quoted (reserved word) — output JSON key stays "position".
create or replace function public.sync_pull_watch_progress_delta(
  p_profile_id int, p_since_event_id bigint, p_limit int
) returns table(
  event_id bigint, operation text, progress_key text, content_id text, content_type text,
  video_id text, season int, episode int, "position" bigint, duration bigint, last_watched bigint
) language sql security definer set search_path = '' as $$
  select ev.event_id, ev.operation, ev.progress_key, ev.content_id, ev.content_type,
         ev.video_id, ev.season, ev.episode, ev.position, ev.duration, ev.last_watched
  from public.watch_progress_events ev
  where ev.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and ev.profile_id = p_profile_id
    and ev.event_id > p_since_event_id
  order by ev.event_id asc                                          -- R8
  limit p_limit
$$;
revoke all     on function public.sync_pull_watch_progress_delta(int, bigint, int) from public, anon;
grant  execute on function public.sync_pull_watch_progress_delta(int, bigint, int) to authenticated;
