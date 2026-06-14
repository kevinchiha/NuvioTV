-- KevBox TV — profiles + profile_locks cloud restore. Run ONCE AFTER get_sync_owner_setup.sql AND the
-- Plan-1/2 setups (sync_delete_profile_data, added later in this file, references those tables). Idempotent.
-- Spec §5.5. KEY COLUMN IS profile_index (NOT profile_id). Highest-risk: sync_pull_profiles() is the
-- un-guarded startup pull — it must never error and always return a non-null profile_index row.

-- 1. Tables.
create table if not exists public.profiles (
  user_id              uuid    not null references auth.users(id) on delete cascade,
  profile_index        int     not null,                  -- the ONE required model field
  name                 text    not null default '',
  avatar_color_hex     text    not null default '#1E88E5',
  uses_primary_addons  boolean not null default false,
  uses_primary_plugins boolean not null default false,
  avatar_id            text,
  avatar_url           text,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  primary key (user_id, profile_index)                    -- R3 upsert target
);

create table if not exists public.profile_locks (
  user_id          uuid    not null references auth.users(id) on delete cascade,
  profile_index    int     not null,
  pin_enabled      boolean not null default false,
  pin_locked_until timestamptz,
  primary key (user_id, profile_index)
);

-- 2. RLS + grants (read-own; writes via SECURITY DEFINER RPCs).
alter table public.profiles      enable row level security;
alter table public.profile_locks enable row level security;
drop policy if exists "read own profiles" on public.profiles;
create policy "read own profiles" on public.profiles for select using (auth.uid() = user_id);
drop policy if exists "read own profile_locks" on public.profile_locks;
create policy "read own profile_locks" on public.profile_locks for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger on public.profiles, public.profile_locks from anon, authenticated;
revoke select on public.profiles, public.profile_locks from anon;
grant  select on public.profiles, public.profile_locks to authenticated;
