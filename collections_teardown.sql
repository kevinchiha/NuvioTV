-- Rollback of collections_setup.sql. Removes ONLY collections objects. Does NOT touch get_sync_owner
-- (shared), auth.users, watch_progress, watched_items, library, profiles, member_*, or telemetry.
-- DROPS the stored collections data. Idempotent.

drop function if exists public.sync_push_collections(int, jsonb);
drop function if exists public.sync_push_collections_for(uuid, int, jsonb);
drop function if exists public.sync_pull_collections(int);

drop table if exists public.collections;

select 'collections' as obj, to_regclass('public.collections') as still_exists;
