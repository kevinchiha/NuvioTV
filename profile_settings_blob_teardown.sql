-- Rollback of profile_settings_blob_setup.sql. Removes ONLY profile_settings_blob objects. Does NOT
-- touch get_sync_owner (shared), auth.users, or any sibling subsystem. DROPS the stored data. Idempotent.

drop function if exists public.sync_push_profile_settings_blob(int, jsonb, text);
drop function if exists public.sync_push_profile_settings_blob_for(uuid, int, jsonb, text);
drop function if exists public.sync_pull_profile_settings_blob(int, text);

drop table if exists public.profile_settings_blob;

select 'profile_settings_blob' as obj, to_regclass('public.profile_settings_blob') as still_exists;
