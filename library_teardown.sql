-- Rollback of library_setup.sql. Removes ONLY library objects. Does NOT touch get_sync_owner
-- (shared), auth.users, watch_progress, watched_items, member_*, or telemetry. DROPS the stored
-- library data. Idempotent.

drop function if exists public.sync_push_library(jsonb, int);
drop function if exists public.sync_push_library_for(uuid, int, jsonb);
drop function if exists public.sync_pull_library(int, int, int);

drop table if exists public.library;

select 'library' as obj, to_regclass('public.library') as still_exists;
