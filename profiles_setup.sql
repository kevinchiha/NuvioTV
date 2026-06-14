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

-- 3. Pull profiles. NO ARGS. SECURITY DEFINER + owner predicate (R1). Exact SupabaseProfile shape (R7,
--    11 keys). CRITICAL (§5.5): the un-guarded startup pull must NEVER error and must always return a
--    row with a non-null profile_index — synthesize a default profile_index=1 row when the owner has none.
create or replace function public.sync_pull_profiles()
returns table(
  id text, user_id text, profile_index int, name text, avatar_color_hex text,
  uses_primary_addons boolean, uses_primary_plugins boolean,
  avatar_id text, avatar_url text, created_at timestamptz, updated_at timestamptz
) language sql security definer set search_path = '' as $$
  with o as (select nullif(public.get_sync_owner(),'')::uuid as uid),
  stored as (
    select null::text as id, p.user_id::text as user_id, p.profile_index, p.name, p.avatar_color_hex,
           p.uses_primary_addons, p.uses_primary_plugins, p.avatar_id, p.avatar_url,
           p.created_at, p.updated_at
    from public.profiles p, o
    where p.user_id = o.uid
  )
  select * from stored
  union all
  -- synthesized default ONLY when the owner has no stored profiles (keeps the un-guarded pull non-empty
  -- and decodable; profile_index=1 is the client's default). Works even for a null owner (returns a
  -- harmless default row) so the call never raises.
  select null::text, (select uid::text from o), 1, ''::text, '#1E88E5'::text,
         false, false, null::text, null::text, now(), now()
  where not exists (select 1 from stored)
$$;
revoke all     on function public.sync_pull_profiles() from public, anon;
grant  execute on function public.sync_pull_profiles() to authenticated;

-- 4. Pull profile_locks. NO ARGS. Owner-scoped. Exact SupabaseProfileLockState shape (profile_index,
--    pin_enabled, pin_locked_until). Empty set is safe (fail-soft).
create or replace function public.sync_pull_profile_locks()
returns table(profile_index int, pin_enabled boolean, pin_locked_until timestamptz)
language sql security definer set search_path = '' as $$
  select pl.profile_index, pl.pin_enabled, pl.pin_locked_until
  from public.profile_locks pl
  where pl.user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
$$;
revoke all     on function public.sync_pull_profile_locks() from public, anon;
grant  execute on function public.sync_pull_profile_locks() to authenticated;
