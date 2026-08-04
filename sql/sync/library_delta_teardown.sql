-- Rollback of library_delta_setup.sql. Removes ONLY the library-delta + device-registry objects
-- and RESTORES sync_push_library_for / sync_push_library to their pre-delta (snapshot-only, no
-- event log) bodies. Does NOT touch the library state table, sync_pull_library, get_sync_owner
-- (shared), auth.users, watch_progress, watched_items, collections, member_*, or telemetry.
-- DROPS the stored library_events history and registered_devices rows. Idempotent.

-- 1. New-fleet + delta + registry functions.
drop function if exists public.sync_push_library_items(jsonb, int, text);
drop function if exists public.sync_delete_library_items(jsonb, int, text);
drop function if exists public.sync_delete_library_items_for(uuid, int, jsonb);
drop function if exists public.sync_get_library_delta_cursor(int);
drop function if exists public.sync_pull_library_delta(int, bigint, int);
drop function if exists public.register_current_device(text, text, text, text, text);

-- 2. Restore the pre-delta push functions (library_setup.sql §3 verbatim) so old-fleet
--    sync_push_library keeps working exactly as before the migration (upsert-only, no events).
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
  end loop;
end $$;

create or replace function public.sync_push_library(
  p_items jsonb, p_profile_id int, p_origin_client_id text default null
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_library_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

revoke all on function public.sync_push_library_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_library(jsonb, int, text) from public, anon;
grant  execute on function public.sync_push_library(jsonb, int, text) to authenticated;

-- 3. Tables (RLS policies + indexes drop with the table).
drop table if exists public.library_events;
drop table if exists public.registered_devices;

-- Sanity: all dropped objects should report NULL (gone).
select 'library_events' as obj, to_regclass('public.library_events') as still_exists
union all select 'registered_devices', to_regclass('public.registered_devices');
