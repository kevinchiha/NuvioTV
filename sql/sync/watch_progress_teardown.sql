-- Rollback of watch_progress_setup.sql. Removes ONLY watch_progress objects. Does NOT touch
-- get_sync_owner (shared), auth.users, member_*, or telemetry. DROPS the stored progress data.
-- Idempotent. Mirrors member_telemetry_teardown.sql.

-- 1. Functions (wrappers + inner fns).
drop function if exists public.sync_push_watch_progress(jsonb, int);
drop function if exists public.sync_push_watch_progress_for(uuid, int, jsonb);
drop function if exists public.sync_pull_watch_progress(int, bigint, int);
drop function if exists public.sync_get_watch_progress_delta_cursor(int);
drop function if exists public.sync_pull_watch_progress_delta(int, bigint, int);
drop function if exists public.sync_delete_watch_progress(jsonb, int);
drop function if exists public.sync_delete_watch_progress_for(uuid, int, jsonb);

-- 2. Tables (RLS policies + indexes drop with the table).
drop table if exists public.watch_progress_events;
drop table if exists public.watch_progress;

-- Sanity: both should report NULL (gone).
select 'watch_progress' as obj, to_regclass('public.watch_progress') as still_exists
union all select 'watch_progress_events', to_regclass('public.watch_progress_events');
