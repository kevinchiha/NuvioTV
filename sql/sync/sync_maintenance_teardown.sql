-- Rollback of sync_maintenance_setup.sql. Removes ONLY the maintenance/observability functions.
-- Does NOT touch any data tables or get_sync_owner. Idempotent.

drop function if exists public.prune_sync_events(int);
drop function if exists public.get_sync_overview();

select 'prune_sync_events' as obj, to_regprocedure('public.prune_sync_events(int)') as still_exists
union all select 'get_sync_overview', to_regprocedure('public.get_sync_overview()');
