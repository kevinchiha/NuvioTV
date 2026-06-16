-- Rollback of get_sync_owner_setup.sql. SELF-GUARDING: get_sync_owner is shared — the
-- watch_progress / watched_items / library / collections / settings / profiles RPCs all call it.
-- Dropping it while any dependent sync_* function exists would make those RPCs raise 42883 at
-- runtime, which the client swallows into a SILENT restore failure. So skip the drop if dependents
-- remain. The canary allowlist (sync_canary_members) is dropped ONLY together with get_sync_owner:
-- the resolver references the table, so dropping the table while the resolver survives would make it
-- raise 42P01. Idempotent.
do $$
declare v_deps int;
begin
  select count(*) into v_deps
  from pg_proc
  where pronamespace = 'public'::regnamespace
    and proname like 'sync\_%'
    and pg_get_functiondef(oid) ilike '%get_sync_owner()%';
  if v_deps > 0 then
    raise notice 'get_sync_owner still has % sync_* dependent(s); NOT dropping it or sync_canary_members. Tear those down first.', v_deps;
  else
    drop function if exists public.get_sync_owner();
    drop table if exists public.sync_canary_members;
    raise notice 'get_sync_owner + sync_canary_members dropped (no dependents).';
  end if;
end $$;
select 'get_sync_owner'      as obj, to_regprocedure('public.get_sync_owner()')::text as still_exists
union all
select 'sync_canary_members' as obj, to_regclass('public.sync_canary_members')::text;
