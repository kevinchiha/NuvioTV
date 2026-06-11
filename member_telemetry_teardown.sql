-- KevBox TV — member activity telemetry FULL TEARDOWN (rollback of member_telemetry_setup.sql).
-- Purely additive feature → this removes ONLY the telemetry objects. It does NOT touch
-- auth.users, member_device, member_access, member_addon, the kevbox_admin role, or any
-- existing RLS/grants. Safe to run against scmqdptagksltnwiveyh. Idempotent (if exists).
--
-- NOTE: this DROPS the collected telemetry data (durations only — no member/auth data).
-- If you only want to STOP ingestion without losing data, use the instant kill-switch instead:
--   create or replace function public.record_heartbeat(p_device_id text, p_app_version text, p_kind text default 'playback')
--     returns void language plpgsql as $$ begin end $$;
--   create or replace function public.record_error(p_device_id text, p_app_version text, p_detail jsonb)
--     returns void language plpgsql as $$ begin end $$;

-- 1. Functions (wrappers first, then inner fns — order irrelevant, all independent).
drop function if exists public.record_heartbeat(text, text, text);
drop function if exists public.record_error(text, text, jsonb);
drop function if exists public.prune_telemetry(int, int);
drop function if exists public.accrue_heartbeat(uuid, text, text, int);
drop function if exists public.record_session_start(uuid, text, text);
drop function if exists public.record_error_event(uuid, text, text, jsonb);

-- 2. Tables (RLS policies + indexes drop automatically with the table). Nothing FKs INTO
--    these, so no cascade surprises beyond the rows themselves.
drop table if exists public.member_event;
drop table if exists public.member_heartbeat;
drop table if exists public.member_activity_daily;

-- Sanity: all three should return 0 rows.
select 'member_activity_daily' as obj, to_regclass('public.member_activity_daily') as still_exists
union all select 'member_heartbeat', to_regclass('public.member_heartbeat')
union all select 'member_event', to_regclass('public.member_event');
