-- Rollback of watched_items_setup.sql. Removes ONLY watched_items objects. Does NOT touch
-- get_sync_owner (shared), auth.users, watch_progress, library, member_*, or telemetry.
-- DROPS the stored watched-history data. Idempotent.

-- 1. Functions (wrappers + inner fns).
drop function if exists public.sync_push_watched_items(jsonb, int);
drop function if exists public.sync_push_watched_items_for(uuid, int, jsonb);
drop function if exists public.sync_pull_watched_items(int, int, int);
drop function if exists public.sync_get_watched_items_delta_cursor(int);
drop function if exists public.sync_pull_watched_items_delta(int, bigint, int);
drop function if exists public.sync_delete_watched_items(int, jsonb);
drop function if exists public.sync_delete_watched_items_for(uuid, int, jsonb);

-- 2. Tables (RLS policies + indexes drop with the table).
drop table if exists public.watched_items_events;
drop table if exists public.watched_items;

-- Sanity: both should report NULL (gone).
select 'watched_items' as obj, to_regclass('public.watched_items') as still_exists
union all select 'watched_items_events', to_regclass('public.watched_items_events');
