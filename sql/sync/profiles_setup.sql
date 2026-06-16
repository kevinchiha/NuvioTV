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
--    11 keys). CRITICAL (§5.5): the un-guarded startup pull must NEVER error (empty is fine — it must not
--    RAISE, or the broad restore aborts). Returns stored profiles, EMPTY when none — must NOT synth a
--    default (the client replaceAllProfiles-on-non-empty would wipe local profiles; see body comment).
create or replace function public.sync_pull_profiles()
returns table(
  id text, user_id text, profile_index int, name text, avatar_color_hex text,
  uses_primary_addons boolean, uses_primary_plugins boolean,
  avatar_id text, avatar_url text, created_at timestamptz, updated_at timestamptz
) language sql security definer set search_path = '' as $$
  -- Stored profiles only; EMPTY when the owner has none (spec §9 T-PROFILE allows "empty OR default row").
  -- Do NOT synthesize a default row: the client (ProfileSyncService.pullFromRemote) calls
  -- profileDataStore.replaceAllProfiles(...) on ANY non-empty pull, which REPLACES the entire local
  -- profile set — a synth default would WIPE a member's local profiles down to one blank profile on the
  -- first post-deploy sync (which pulls BEFORE any push has populated the cloud). Empty is still
  -- never-error (decodeList -> empty list, no throw), so the un-guarded broad-restore pull does not
  -- abort; a NULL owner also yields empty.
  select null::text as id, p.user_id::text as user_id, p.profile_index, p.name, p.avatar_color_hex,
         p.uses_primary_addons, p.uses_primary_plugins, p.avatar_id, p.avatar_url,
         p.created_at, p.updated_at
  from public.profiles p
  where p.user_id = nullif(public.get_sync_owner(),'')::uuid
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

-- 5. Push profiles (UI-only; upsert by profile_index, last-write-wins). p_client_max_profiles bounds
--    the accepted index range (skip <1 or >max — defensive; never stores out-of-range rows). R4 no-op.
create or replace function public.sync_push_profiles_for(
  p_owner uuid, p_client_max_profiles int, p_profiles jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; v_idx int;
begin
  if p_owner is null or p_profiles is null then return; end if;     -- R4
  for e in select value from jsonb_array_elements(p_profiles) as t(value) loop
    v_idx := nullif(e->>'profile_index','')::int;
    if v_idx is null or v_idx < 1
       or (p_client_max_profiles is not null and v_idx > p_client_max_profiles) then
      continue;                                                     -- skip malformed / out-of-range
    end if;
    insert into public.profiles as p (
      user_id, profile_index, name, avatar_color_hex,
      uses_primary_addons, uses_primary_plugins, avatar_id, avatar_url)
    values (
      p_owner, v_idx, coalesce(e->>'name',''), coalesce(e->>'avatar_color_hex','#1E88E5'),
      coalesce((e->>'uses_primary_addons')::boolean, false),
      coalesce((e->>'uses_primary_plugins')::boolean, false),
      e->>'avatar_id', e->>'avatar_url')
    on conflict (user_id, profile_index) do update
      set name=excluded.name, avatar_color_hex=excluded.avatar_color_hex,
          uses_primary_addons=excluded.uses_primary_addons,
          uses_primary_plugins=excluded.uses_primary_plugins,
          avatar_id=excluded.avatar_id, avatar_url=excluded.avatar_url, updated_at=now();
  end loop;
end $$;

create or replace function public.sync_push_profiles(
  p_client_max_profiles int, p_profiles jsonb
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_profiles_for(
    nullif(public.get_sync_owner(),'')::uuid, p_client_max_profiles, p_profiles)
$$;

-- 6. Delete all of the owner's synced data for one profile (UI-only; off the restore path). p_profile_id
--    is the data-table int (== profile_index). The DEFAULT profile (1) is SERVER-GUARDED to a no-op,
--    mirroring the client guard — a data-destructive RPC must not wipe the member's primary profile.
--    plpgsql (late-bound) so it can reference Plan-1/2 tables even if applied before them; at runtime
--    (deploy order: Plans 1-2 first) all tables exist.
create or replace function public.sync_delete_profile_data_for(p_owner uuid, p_profile_id int)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_owner is null or p_profile_id = 1 then return; end if;       -- R4 + default-profile guard
  delete from public.watch_progress         where user_id=p_owner and profile_id=p_profile_id;
  delete from public.watch_progress_events  where user_id=p_owner and profile_id=p_profile_id;
  delete from public.watched_items          where user_id=p_owner and profile_id=p_profile_id;
  delete from public.watched_items_events   where user_id=p_owner and profile_id=p_profile_id;
  delete from public.library                where user_id=p_owner and profile_id=p_profile_id;
  delete from public.collections            where user_id=p_owner and profile_id=p_profile_id;
  delete from public.home_catalog_settings  where user_id=p_owner and profile_id=p_profile_id;
  delete from public.profile_settings_blob  where user_id=p_owner and profile_id=p_profile_id;
  delete from public.profile_locks          where user_id=p_owner and profile_index=p_profile_id;
  delete from public.profiles               where user_id=p_owner and profile_index=p_profile_id;
end $$;

create or replace function public.sync_delete_profile_data(p_profile_id int)
returns void language sql security definer set search_path = '' as $$
  select public.sync_delete_profile_data_for(nullif(public.get_sync_owner(),'')::uuid, p_profile_id)
$$;

-- 7. Function ACLs.
revoke all on function public.sync_push_profiles_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_profiles(int, jsonb) from public, anon;
grant  execute on function public.sync_push_profiles(int, jsonb) to authenticated;
revoke all on function public.sync_delete_profile_data_for(uuid, int) from public, anon, authenticated;
revoke all     on function public.sync_delete_profile_data(int) from public, anon;
grant  execute on function public.sync_delete_profile_data(int) to authenticated;
