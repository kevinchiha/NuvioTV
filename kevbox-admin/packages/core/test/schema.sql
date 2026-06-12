create extension if not exists pgcrypto;
create schema if not exists auth;

create table auth.users (
  id         uuid primary key default gen_random_uuid(),
  email      text unique,
  created_at timestamptz not null default now()
);

create table public.member_addon (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  url         text not null,
  enabled     boolean not null default true,
  sort_order  int not null default 0,
  updated_at  timestamptz not null default now(),
  unique (user_id, url)
);

create view public.member_addon_v as
  select m.*, u.email as auth_email
  from public.member_addon m join auth.users u on u.id = m.user_id;

-- Mirrors the production public.kevbox_auth_users view (owned by postgres, runs with owner
-- privileges) that core queries instead of auth.users directly. On Supabase the auth schema is
-- owned by supabase_admin and the least-priv kevbox_admin role cannot be granted USAGE on it, so
-- the admin reads member emails through this owner-privileged public view (spec §4.1). Exposes
-- only id/email/created_at. Here in the test DB it is a plain view over the local auth.users.
create view public.kevbox_auth_users as
  select id, email, created_at from auth.users;

-- Mirrors the production default_member_addons() shape. Test URLs (4 distinct rows) — the
-- core logic only depends on there being N default rows, not on the exact prod URLs.
create function public.default_member_addons() returns table (url text, sort_order int)
  language sql immutable as $$
  values
    ('https://v3-cinemeta.strem.io', 0),
    ('https://opensubtitlesv3-pro.example/cfg/manifest.json', 1),
    ('https://opensubtitles-v3.strem.io', 2),
    ('https://netflix-catalog.example/cfg/manifest.json', 3)
$$;

-- Access kill-switch + one-device-per-member limit tables. Copied EXACTLY (table DDL only) from the
-- prod member_access_setup.sql / member_device_setup.sql. The load-bearing PKs/defaults/FKs the core
-- upserts depend on are carried (user_id PK / composite (user_id, device_id) PK for on conflict;
-- not null default true / default 1; references auth.users(id) on delete cascade). Deliberately NO
-- RLS, policies, grants, seed trigger, or back-fill: their absence leaves test members UNSEEDED, which
-- is exactly what makes the no-row defaults (active=true, max=1) testable (plan §5.1).
create table public.member_access (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  active     boolean not null default true,
  updated_at timestamptz not null default now()
);

create table public.member_device (
  user_id     uuid not null references auth.users(id) on delete cascade,
  device_id   text not null,
  device_name text,
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  primary key (user_id, device_id)
);

create table public.member_device_policy (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  max_devices int not null default 1,
  updated_at  timestamptz not null default now()
);

-- ===== Member activity telemetry (durations only) =====
-- Per-member-per-day watch-time rollup (authoritative metric).
create table public.member_activity_daily (
  user_id          uuid not null references auth.users(id) on delete cascade,
  day              date not null,
  watch_seconds    int  not null default 0,
  heartbeats       int  not null default 0,
  sessions         int  not null default 0,
  last_app_version text,
  updated_at       timestamptz not null default now(),
  primary key (user_id, day)
);

-- Accrual baseline + last-known app version, per (member, device). Decoupled from member_device.
create table public.member_heartbeat (
  user_id        uuid not null references auth.users(id) on delete cascade,
  device_id      text not null,
  last_heartbeat timestamptz not null default now(),
  app_version    text,
  primary key (user_id, device_id)
);

-- Notable events only (session_start, playback_error). Pruned at 90 days. NEVER content/secrets.
create table public.member_event (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references auth.users(id) on delete cascade,
  device_id   text,
  occurred_at timestamptz not null default now(),
  kind        text not null check (kind in ('session_start','playback_error')),
  app_version text,
  detail      jsonb
);
create index member_event_user_time on public.member_event (user_id, occurred_at desc);
create index member_event_kind_time on public.member_event (kind, occurred_at desc);

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

-- Retention prune (Phase 4). Strict boundaries: an event/day exactly AT the cutoff is KEPT; only
-- rows strictly past it are deleted. KEEP BYTE-IDENTICAL with member_telemetry_setup.sql (M2 drift).
create or replace function public.prune_telemetry(p_event_days int default 90, p_aggregate_days int default 396)
  returns text language plpgsql security definer set search_path = '' as $$
declare v_events int; v_days int;
begin
  delete from public.member_event where occurred_at < now() - make_interval(days => p_event_days);
  get diagnostics v_events = row_count;
  delete from public.member_activity_daily where day < (now() at time zone 'utc')::date - p_aggregate_days;
  get diagnostics v_days = row_count;
  return format('pruned %s events, %s daily rows', v_events, v_days);
end $$;

-- ===== KevBox member enrollment (spec §4) =====
-- Sidecar per auth.users member. aiostreams_name is the CANONICAL verbatim live
-- allowlist token (never re-derived from email at migration time, C1). Name
-- uniqueness binds ACTIVE members only (partial index, H5) so a departed member's
-- name can be reused. premiumize_key_enc nullable (name-only after a backfill miss).
create table public.kevbox_member (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  aiostreams_name    text not null
                       check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  premiumize_key_enc text,
  enrolled           boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create unique index kevbox_member_name_active
  on public.kevbox_member (aiostreams_name) where enrolled;

-- Safety net for any legacy allowlist name that does not resolve to an auth.users row.
create table public.kevbox_allowlist_extra (
  aiostreams_name text primary key
                    check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  note            text,
  created_at      timestamptz not null default now()
);
