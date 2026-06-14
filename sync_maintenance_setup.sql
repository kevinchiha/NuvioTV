-- KevBox TV — cloud-restore maintenance/observability. Run ONCE AFTER the Plan-1/2/3 data setups
-- (references watch_progress/watched_items/library/collections/home_catalog/profiles). Idempotent.
-- Spec §7 (get_sync_overview), §10/R10 (prune_sync_events). No secrets.

-- 1. R10 retention. Deletes ONLY append-only event-log rows older than p_event_days. NEVER touches
--    state tables (snapshot pulls remain the full-fidelity restore). Mirrors prune_telemetry.
create or replace function public.prune_sync_events(p_event_days int default 180)
  returns text language plpgsql security definer set search_path = '' as $$
declare v_wp bigint; v_wi bigint;
begin
  delete from public.watch_progress_events where created_at < now() - make_interval(days => p_event_days);
  get diagnostics v_wp = row_count;
  delete from public.watched_items_events  where created_at < now() - make_interval(days => p_event_days);
  get diagnostics v_wi = row_count;
  return format('pruned %s watch_progress_events, %s watched_items_events', v_wp, v_wi);
end $$;

-- Admin/cron-only: revoke from all app roles. Run via pg_cron (as the job owner) or manual admin psql.
-- The branch has no kevbox_admin, so we do NOT grant to it (unlike prune_telemetry); the definer/owner
-- and any superuser/cron context can always execute.
revoke all on function public.prune_sync_events(int) from public, anon, authenticated;
-- Suggested schedule (run manually on prod once pg_cron is available):
--   select cron.schedule('prune_sync_events_daily', '30 4 * * *', $$ select public.prune_sync_events(180) $$);
