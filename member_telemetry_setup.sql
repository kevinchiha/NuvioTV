-- KevBox TV — member activity telemetry (durations only). Run ONCE against the KevBox Supabase
-- project (scmqdptagksltnwiveyh). Idempotent — safe to re-run. No secrets here.
-- Plan: docs/superpowers/plans/2026-06-09-member-activity-telemetry.md
-- Pattern mirrors member_device_setup.sql: RLS read-own, writes RPC-only, admin via grants.

-- 1. Tables -----------------------------------------------------------------------
create table if not exists public.member_activity_daily (
  user_id uuid not null references auth.users(id) on delete cascade,
  day date not null, watch_seconds int not null default 0, heartbeats int not null default 0,
  sessions int not null default 0, last_app_version text,
  updated_at timestamptz not null default now(), primary key (user_id, day)
);
create table if not exists public.member_heartbeat (
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null, last_heartbeat timestamptz not null default now(),
  app_version text, primary key (user_id, device_id)
);
create table if not exists public.member_event (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text, occurred_at timestamptz not null default now(),
  kind text not null check (kind in ('session_start','playback_error')), app_version text, detail jsonb
);
create index if not exists member_event_user_time on public.member_event (user_id, occurred_at desc);
create index if not exists member_event_kind_time on public.member_event (kind, occurred_at desc);
-- Fleet-wide queries (leaderboard / going-dark / stats) filter by `day` across ALL users; the PK is
-- (user_id, day), so a day-only range can't use it efficiently. Cheap insurance at any scale (L1).
create index if not exists member_activity_daily_day on public.member_activity_daily (day);

alter table public.member_activity_daily enable row level security;
alter table public.member_heartbeat      enable row level security;
alter table public.member_event          enable row level security;

-- 2. RLS: members read only their own rows. No write policy → writes go through RPCs only.
drop policy if exists "read own activity" on public.member_activity_daily;
create policy "read own activity" on public.member_activity_daily for select using (auth.uid() = user_id);
drop policy if exists "read own heartbeat" on public.member_heartbeat;
create policy "read own heartbeat" on public.member_heartbeat for select using (auth.uid() = user_id);
drop policy if exists "read own events" on public.member_event;
create policy "read own events" on public.member_event for select using (auth.uid() = user_id);

-- 2b. Revoke default DML grants from anon/authenticated (mirrors member_device_setup.sql).
revoke insert, update, delete, truncate, references, trigger
  on public.member_activity_daily, public.member_heartbeat, public.member_event
  from anon, authenticated;

-- 2c. Admin reads (kevbox_admin is BYPASSRLS but still needs table grants).
grant select on public.member_activity_daily, public.member_heartbeat, public.member_event to kevbox_admin;

-- 3. Inner functions (explicit user id) — copied BYTE-IDENTICAL from packages/core/test/schema.sql
--    (Tasks 1.2/1.3), where they have automated TDD coverage. KEEP THESE IN SYNC (M2 drift risk):
--    if you change one copy, change the other and re-run `npx vitest run test/telemetry.test.ts`.

-- Capped wall-clock accrual. Inner fn takes explicit user id → unit-testable without a JWT.
-- Concurrency (L3): the daily upsert is atomic (ON CONFLICT row lock). Two concurrent beats for the
-- same (user,device) could each read the old baseline and accrue ≤ CAP; with the one-device limit and
-- ~60s sequential beats this is improbable and bounded by the 120s cap — so no advisory lock is used.
create or replace function public.accrue_heartbeat(
  p_user_id uuid, p_device_id text, p_app_version text, p_cap_seconds int default 120
) returns int language plpgsql security definer set search_path = '' as $$
declare v_last timestamptz; v_accrued int;
begin
  if p_user_id is null then return 0; end if;
  select last_heartbeat into v_last
    from public.member_heartbeat where user_id = p_user_id and device_id = p_device_id;
  if v_last is null then
    v_accrued := 0;                                   -- first beat: establish baseline only
  else
    v_accrued := least(greatest(0, floor(extract(epoch from (now() - v_last)))::int), p_cap_seconds);
  end if;
  insert into public.member_activity_daily(user_id, day, watch_seconds, heartbeats, last_app_version, updated_at)
    values (p_user_id, (now() at time zone 'utc')::date, v_accrued, 1, p_app_version, now())
  on conflict (user_id, day) do update
    set watch_seconds = public.member_activity_daily.watch_seconds + excluded.watch_seconds,
        heartbeats    = public.member_activity_daily.heartbeats + 1,
        last_app_version = excluded.last_app_version,
        updated_at = now();
  insert into public.member_heartbeat(user_id, device_id, last_heartbeat, app_version)
    values (p_user_id, p_device_id, now(), p_app_version)
  on conflict (user_id, device_id) do update
    set last_heartbeat = now(), app_version = excluded.app_version;
  return v_accrued;
end $$;

create or replace function public.record_session_start(
  p_user_id uuid, p_device_id text, p_app_version text
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_user_id is null then return; end if;
  insert into public.member_activity_daily(user_id, day, sessions, last_app_version, updated_at)
    values (p_user_id, (now() at time zone 'utc')::date, 1, p_app_version, now())
  on conflict (user_id, day) do update
    set sessions = public.member_activity_daily.sessions + 1,
        last_app_version = excluded.last_app_version, updated_at = now();
  insert into public.member_event(user_id, device_id, kind, app_version)
    values (p_user_id, p_device_id, 'session_start', p_app_version);
  insert into public.member_heartbeat(user_id, device_id, last_heartbeat, app_version)
    values (p_user_id, p_device_id, now(), p_app_version)
  on conflict (user_id, device_id) do update set last_heartbeat = now(), app_version = excluded.app_version;
end $$;

create or replace function public.record_error_event(
  p_user_id uuid, p_device_id text, p_app_version text, p_detail jsonb
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_user_id is null then return; end if;
  insert into public.member_event(user_id, device_id, kind, app_version, detail)
    values (
      p_user_id, p_device_id, 'playback_error', p_app_version,
      jsonb_build_object(                                   -- key allowlist: no secrets/urls/titles
        'code', left(coalesce(p_detail->>'code',''), 32),
        -- message_short (spec §7): strip URL-ish tokens, then cap hard at 120.
        'message_short', left(regexp_replace(coalesce(p_detail->>'message',''), '\S*://\S*', '[url]', 'g'), 120)
      )
    );
end $$;

-- 3b. LOCK DOWN the inner functions (CRITICAL — H1). They are SECURITY DEFINER and take an explicit
--     p_user_id, so the Postgres default `EXECUTE to PUBLIC` would let any authenticated REST
--     client call them with a FOREIGN uid and forge/inflate another member's telemetry —
--     bypassing the entire RLS / JWT-uid model. Only the owner-running wrappers (section 4) call
--     them, and that call succeeds regardless of caller grants because the wrapper runs as owner.
revoke all on function public.accrue_heartbeat(uuid, text, text, int)     from public, anon, authenticated;
revoke all on function public.record_session_start(uuid, text, text)      from public, anon, authenticated;
revoke all on function public.record_error_event(uuid, text, text, jsonb) from public, anon, authenticated;

-- 4. JWT RPC wrappers — the ONLY write path the app uses. uid from JWT (tamper-proof).
create or replace function public.record_heartbeat(
  p_device_id text, p_app_version text, p_kind text default 'playback'
) returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then return; end if;             -- unauthenticated: silently no-op
  if p_kind = 'session_start' then                  -- p_kind allowlist (spec §7): unknown kinds ignored
    perform public.record_session_start(v_uid, p_device_id, p_app_version);
  elsif p_kind = 'playback' then
    perform public.accrue_heartbeat(v_uid, p_device_id, p_app_version, 120);
  else
    return;                                          -- not 'session_start' or 'playback' → no-op
  end if;
end $$;
revoke all on function public.record_heartbeat(text, text, text) from public, anon;
grant execute on function public.record_heartbeat(text, text, text) to authenticated;

create or replace function public.record_error(
  p_device_id text, p_app_version text, p_detail jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid();
begin
  if v_uid is null then return; end if;
  perform public.record_error_event(v_uid, p_device_id, p_app_version, p_detail);
end $$;
revoke all on function public.record_error(text, text, jsonb) from public, anon;
grant execute on function public.record_error(text, text, jsonb) to authenticated;

-- Sanity
select count(*) as activity_rows from public.member_activity_daily;

-- ============================================================================================
-- ROLLBACK — stop all telemetry ingestion instantly (no app re-sideload). Replace the wrappers with
-- void no-op bodies. The load-bearing gotcha, verified against Postgres 16 (M11):
--   `create or replace function` CANNOT rename input parameters OR drop their defaults, so the
--   no-op signature MUST match the live one EXACTLY — keep the param names AND `p_kind`'s
--   `default 'playback'`. An unnamed `(text,text,text)` form errors out ("cannot change name of
--   input parameter" / "cannot remove parameter defaults") and the kill-switch silently fails.
--   Use a plpgsql `begin end` body below (a bare `language sql as $$ select $$` also parses on PG16,
--   but plpgsql begin/end is the unambiguous void no-op).
--   create or replace function public.record_heartbeat(p_device_id text, p_app_version text, p_kind text default 'playback')
--     returns void language plpgsql as $$ begin end $$;
--   create or replace function public.record_error(p_device_id text, p_app_version text, p_detail jsonb)
--     returns void language plpgsql as $$ begin end $$;
--   -- To restore: re-run the ENTIRE member_telemetry_setup.sql (idempotent). Re-running section 4
--   --   alone leaves the wrappers calling section-3 inner fns ("function does not exist").
--   -- To purge: truncate member_event, member_heartbeat, member_activity_daily;  (FK only to auth.users)
-- ============================================================================================
