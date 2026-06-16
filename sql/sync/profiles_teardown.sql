-- Rollback of profiles_setup.sql. Removes ONLY profiles objects (incl. the cross-subsystem
-- sync_delete_profile_data RPC). Does NOT touch get_sync_owner (shared), auth.users, or any sibling
-- DATA (watch_progress/watched_items/library/collections/home_catalog/profile_settings). Idempotent.

drop function if exists public.sync_pull_profiles();
drop function if exists public.sync_pull_profile_locks();
drop function if exists public.sync_push_profiles(int, jsonb);
drop function if exists public.sync_push_profiles_for(uuid, int, jsonb);
drop function if exists public.sync_delete_profile_data(int);
drop function if exists public.sync_delete_profile_data_for(uuid, int);

drop table if exists public.profile_locks;
drop table if exists public.profiles;

select 'profiles' as obj, to_regclass('public.profiles') as still_exists
union all select 'profile_locks', to_regclass('public.profile_locks');
