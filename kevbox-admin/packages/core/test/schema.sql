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
