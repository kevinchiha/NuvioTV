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

-- 2. get_sync_overview (§7). Owner-scoped per-profile row counts as a single jsonb object decoded by
--    SyncOverviewResponse (addons/plugins/library_items/watch_progress/watched_items: {profileId->count};
--    profiles: {profileIndex->{name,color}}). addons/plugins are empty ({}) — member_addon owns addons,
--    plugins are global. Member-facing + doubles as a §8 detection probe. NULL owner => all-empty object.
--    `language sql` => all referenced tables must exist at CREATE time (apply after Plan-1/2/3 data setups).
create or replace function public.get_sync_overview()
  returns jsonb language sql security definer set search_path = '' as $$
  with o as (select nullif(public.get_sync_owner(),'')::uuid as uid)
  select jsonb_build_object(
    'addons',  '{}'::jsonb,
    'plugins', '{}'::jsonb,
    'library_items', coalesce((
      select jsonb_object_agg(profile_id::text, n) from (
        select l.profile_id, count(*) n from public.library l, o where l.user_id = o.uid group by l.profile_id) s), '{}'::jsonb),
    'watch_progress', coalesce((
      select jsonb_object_agg(profile_id::text, n) from (
        select w.profile_id, count(*) n from public.watch_progress w, o where w.user_id = o.uid group by w.profile_id) s), '{}'::jsonb),
    'watched_items', coalesce((
      select jsonb_object_agg(profile_id::text, n) from (
        select wi.profile_id, count(*) n from public.watched_items wi, o where wi.user_id = o.uid group by wi.profile_id) s), '{}'::jsonb),
    'profiles', coalesce((
      select jsonb_object_agg(profile_index::text, jsonb_build_object('name', name, 'color', avatar_color_hex)) from (
        select p.profile_index, p.name, p.avatar_color_hex from public.profiles p, o where p.user_id = o.uid) s), '{}'::jsonb)
  )
$$;
revoke all     on function public.get_sync_overview() from public, anon;
grant  execute on function public.get_sync_overview() to authenticated;
