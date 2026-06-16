-- Rollback of home_catalog_settings_setup.sql. Removes ONLY home_catalog_settings objects. Does NOT
-- touch get_sync_owner (shared), auth.users, or any sibling subsystem. DROPS the stored data. Idempotent.

drop function if exists public.sync_push_home_catalog_settings(int, jsonb, text);
drop function if exists public.sync_push_home_catalog_settings_for(uuid, int, jsonb, text);
drop function if exists public.sync_pull_home_catalog_settings(int, text);

drop table if exists public.home_catalog_settings;

select 'home_catalog_settings' as obj, to_regclass('public.home_catalog_settings') as still_exists;
