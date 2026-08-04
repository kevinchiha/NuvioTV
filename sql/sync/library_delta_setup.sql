-- KevBox TV — library event-sourced sync (upstream 0.8.1 wire) + fleet device registry. Run ONCE
-- against the KevBox Supabase project (scmqdptagksltnwiveyh) AFTER library_setup.sql. Idempotent.
-- No secrets. Mirrors the watched_items event-sourced design (watched_items_setup.sql).
--
-- What this adds on top of library_setup.sql (snapshot-only):
--   * public.library_events            — append-only upsert/delete event log (delta cursor source)
--   * public.registered_devices        — per-(user, installation_id) device registry
--   * sync_push_library_items(...)     — NEW upstream name; delegates to sync_push_library_for
--   * sync_delete_library_items(...)   — NEW delete RPC (+ inner _for)
--   * sync_get_library_delta_cursor()  — NEW; max event_id for owner+profile (0 if none)
--   * sync_pull_library_delta(...)     — NEW; event log scan after a cursor
--   * register_current_device(...)     — NEW; upsert into registered_devices (auth + onResume)
-- and it REPLACES sync_push_library_for / sync_push_library in place so OLD-fleet pushes
-- (0.8.19-beta calls sync_push_library with 2 args) keep working AND append upsert events,
-- making old-fleet mutations visible to new-fleet delta pulls. No backfill: new clients take a
-- full sync_pull_library snapshot at the current cursor first, so only post-migration mutations
-- need events. sync_pull_library itself is UNCHANGED.

-- 1. Event log (mirrors watched_items_events; library payload columns). ------------------------
create table if not exists public.library_events (
  event_id       bigint generated always as identity primary key,  -- global monotonic cursor
  user_id        uuid   not null references auth.users(id) on delete cascade,
  profile_id     int    not null default 1,
  operation      text   not null check (operation in ('upsert','delete')),
  content_id     text   not null,
  content_type   text   not null default '',
  name           text   not null default '',
  poster         text,
  poster_shape   text   not null default 'POSTER',
  background     text,
  description    text,
  release_info   text,
  imdb_rating    real,
  genres         text[] not null default '{}',
  addon_base_url text,
  added_at       bigint not null default 0,             -- zeroed on delete events
  created_at     timestamptz not null default now()
);
create index if not exists library_events_owner_idx
  on public.library_events (user_id, profile_id, event_id);

-- 2. Device registry. register_current_device upserts one row per (user, installation_id). -----
create table if not exists public.registered_devices (
  user_id         uuid   not null references auth.users(id) on delete cascade,
  installation_id text   not null,
  client_name     text   not null default '',
  client_version  text   not null default '',
  platform        text   not null default '',
  device_name     text,
  first_seen_at   timestamptz not null default now(),
  last_seen_at    timestamptz not null default now(),
  primary key (user_id, installation_id)
);

-- 3. RLS: read own rows only. Writes go through SECURITY DEFINER RPCs (below).
alter table public.library_events     enable row level security;
alter table public.registered_devices enable row level security;
drop policy if exists "read own library_events" on public.library_events;
create policy "read own library_events" on public.library_events
  for select using (auth.uid() = user_id);
drop policy if exists "read own registered_devices" on public.registered_devices;
create policy "read own registered_devices" on public.registered_devices
  for select using (auth.uid() = user_id);

-- 3b. Revoke default DML; writes are RPC-only. SELECT stays for authenticated (RLS-scoped); anon none.
revoke insert, update, delete, truncate, references, trigger
  on public.library_events, public.registered_devices from anon, authenticated;
revoke select on public.library_events, public.registered_devices from anon;
grant  select on public.library_events, public.registered_devices to authenticated;

-- 4. Push (REPLACES the snapshot-only inner fn from library_setup.sql): same last-write-wins
--    upsert, PLUS one 'upsert' event per item. Library push is unguarded (no watched_at-style
--    guard), so every pushed item changes the row => event appended unconditionally. R4 NULL-owner
--    no-op. Both the OLD wrapper (sync_push_library, old fleet) and the NEW wrapper
--    (sync_push_library_items, 0.8.1+) delegate here.
create or replace function public.sync_push_library_for(
  p_owner uuid, p_profile_id int, p_items jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb;
begin
  if p_owner is null or p_items is null then return; end if;     -- R4
  for e in select value from jsonb_array_elements(p_items) as t(value) loop
    insert into public.library as lib(
      user_id, profile_id, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url, added_at)
    values (
      p_owner, p_profile_id, e->>'content_id', e->>'content_type', coalesce(e->>'name',''),
      e->>'poster', coalesce(e->>'poster_shape','POSTER'), e->>'background', e->>'description',
      e->>'release_info', nullif(e->>'imdb_rating','')::real,
      -- genres: guard the type so an explicit JSON `"genres": null` can't raise
      -- "cannot extract elements from a scalar" (absent key already yields '{}'). Defense-in-depth —
      -- the verified client always sends a JSON array, but this makes the push total over any input.
      case when jsonb_typeof(e->'genres') = 'array'
           then coalesce((select array_agg(g) from jsonb_array_elements_text(e->'genres') as t(g)), '{}')
           else '{}' end,
      e->>'addon_base_url', coalesce((e->>'added_at')::bigint, 0))
    on conflict (user_id, profile_id, content_id) do update
      set content_type=excluded.content_type, name=excluded.name, poster=excluded.poster,
          poster_shape=excluded.poster_shape, background=excluded.background,
          description=excluded.description, release_info=excluded.release_info,
          imdb_rating=excluded.imdb_rating, genres=excluded.genres,
          addon_base_url=excluded.addon_base_url, added_at=excluded.added_at, updated_at=now();

    insert into public.library_events(
      user_id, profile_id, operation, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url, added_at)
    values (
      p_owner, p_profile_id, 'upsert', e->>'content_id', e->>'content_type', coalesce(e->>'name',''),
      e->>'poster', coalesce(e->>'poster_shape','POSTER'), e->>'background', e->>'description',
      e->>'release_info', nullif(e->>'imdb_rating','')::real,
      case when jsonb_typeof(e->'genres') = 'array'
           then coalesce((select array_agg(g) from jsonb_array_elements_text(e->'genres') as t(g)), '{}')
           else '{}' end,
      e->>'addon_base_url', coalesce((e->>'added_at')::bigint, 0));
  end loop;
end $$;

-- 4b. OLD-fleet wrapper (0.8.19-beta calls sync_push_library with 2 args). 3-arg-with-default, NO
--     2-arg overload (PostgREST PGRST203 ambiguity). p_origin_client_id accepted and ignored
--     (echo-suppression is client-side), same as the other sync wrappers.
create or replace function public.sync_push_library(
  p_items jsonb, p_profile_id int, p_origin_client_id text default null
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_library_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

-- 4c. NEW-fleet wrapper (upstream 0.8.1 name). Identical body, new entry point.
create or replace function public.sync_push_library_items(
  p_items jsonb, p_profile_id int, p_origin_client_id text default null
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_library_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

-- 4d. Function ACLs (inner _for revoked from members; wrappers are the only member-facing entries).
revoke all on function public.sync_push_library_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_library(jsonb, int, text) from public, anon;
grant  execute on function public.sync_push_library(jsonb, int, text) to authenticated;
revoke all     on function public.sync_push_library_items(jsonb, int, text) from public, anon;
grant  execute on function public.sync_push_library_items(jsonb, int, text) to authenticated;

-- 5. Delete. p_keys = array of OBJECTS {content_id, content_type}. content_id alone is the row's
--    identity (library PK = (user_id, profile_id, content_id)); content_type is accepted for wire
--    parity and NOT matched on, so a stale client-side type can't strand the row. One 'delete'
--    event per removed row carrying the deleted row's REAL content_type and a zeroed payload
--    (mirrors watched_items R7). p_origin_client_id accepted and ignored.
create or replace function public.sync_delete_library_items_for(
  p_owner uuid, p_profile_id int, p_keys jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; r record;
begin
  if p_owner is null or p_keys is null then return; end if;     -- R4
  for e in select value from jsonb_array_elements(p_keys) as t(value) loop
    for r in
      delete from public.library lib
      where lib.user_id = p_owner and lib.profile_id = p_profile_id
        and lib.content_id = e->>'content_id'
      returning lib.content_id, lib.content_type
    loop
      insert into public.library_events(
        user_id, profile_id, operation, content_id, content_type)
      values (p_owner, p_profile_id, 'delete', r.content_id, r.content_type);
    end loop;
  end loop;
end $$;

create or replace function public.sync_delete_library_items(
  p_keys jsonb, p_profile_id int, p_origin_client_id text default null
) returns void language sql security definer set search_path = '' as $$
  select public.sync_delete_library_items_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_keys)
$$;

-- 5b. Function ACLs: lock the inner _for fn, expose only the wrapper to members.
revoke all on function public.sync_delete_library_items_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_delete_library_items(jsonb, int, text) from public, anon;
grant  execute on function public.sync_delete_library_items(jsonb, int, text) to authenticated;

-- 6. Delta cursor: latest event_id for THIS owner+profile, coalesced to 0 (bare max() NULL would
--    crash a non-wrapping client, same as watched_items R5).
create or replace function public.sync_get_library_delta_cursor(p_profile_id int)
  returns bigint language sql security definer set search_path = '' as $$
  select coalesce(max(event_id), 0)
  from public.library_events
  where user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and profile_id = p_profile_id
$$;
revoke all     on function public.sync_get_library_delta_cursor(int) from public, anon;
grant  execute on function public.sync_get_library_delta_cursor(int) to authenticated;

-- 7. Delta pull: owner-scoped (R1), event_id > cursor, ASC, limited (R8). Delete event rows carry
--    the zeroed payload written by sync_delete_library_items_for (content_id + real content_type
--    only; name '', poster NULL, ..., added_at 0).
create or replace function public.sync_pull_library_delta(
  p_profile_id int, p_since_event_id bigint, p_limit int
) returns table(
  event_id bigint, operation text, content_id text, content_type text, name text, poster text,
  poster_shape text, background text, description text, release_info text,
  imdb_rating real, genres text[], addon_base_url text, added_at bigint
) language sql security definer set search_path = '' as $$
  select ev.event_id, ev.operation, ev.content_id, ev.content_type, ev.name, ev.poster,
         ev.poster_shape, ev.background, ev.description, ev.release_info,
         ev.imdb_rating, ev.genres, ev.addon_base_url, ev.added_at
  from public.library_events ev
  where ev.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and ev.profile_id = p_profile_id
    and ev.event_id > p_since_event_id
  order by ev.event_id asc                                          -- R8
  limit p_limit
$$;
revoke all     on function public.sync_pull_library_delta(int, bigint, int) from public, anon;
grant  execute on function public.sync_pull_library_delta(int, bigint, int) to authenticated;

-- 8. Device registry. Called on every app auth + onResume; upserts (user_id, installation_id),
--    refreshing client metadata + last_seen_at (first_seen_at sticks). Identifies the user via
--    auth.uid() DIRECTLY (like the member_* RPCs) — this is fleet telemetry, NOT canary-gated
--    cloud-restore sync, so it must not go through get_sync_owner.
create or replace function public.register_current_device(
  p_installation_id text, p_client_name text, p_client_version text,
  p_platform text, p_device_name text
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null or p_installation_id is null then return; end if;
  insert into public.registered_devices as d(
    user_id, installation_id, client_name, client_version, platform, device_name)
  values (
    auth.uid(), p_installation_id, coalesce(p_client_name,''), coalesce(p_client_version,''),
    coalesce(p_platform,''), p_device_name)
  on conflict (user_id, installation_id) do update
    set client_name=excluded.client_name, client_version=excluded.client_version,
        platform=excluded.platform, device_name=excluded.device_name, last_seen_at=now();
end $$;
revoke all     on function public.register_current_device(text, text, text, text, text) from public, anon;
grant  execute on function public.register_current_device(text, text, text, text, text) to authenticated;
