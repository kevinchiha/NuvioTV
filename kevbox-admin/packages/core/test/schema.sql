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
