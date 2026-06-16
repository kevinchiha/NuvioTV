-- KevBox TV — profile-settings blob cloud restore (SNAPSHOT JSON blob; per-platform). Run ONCE AFTER
-- get_sync_owner_setup.sql. Idempotent. Spec §5.4. settings_json = {version, features:{...}} verbatim.
-- SEPARATE table from home_catalog_settings (both hold a "tv" row).

-- 1. Table.
create table if not exists public.profile_settings_blob (
  user_id       uuid   not null references auth.users(id) on delete cascade,
  profile_id    int    not null default 1,
  platform      text   not null,
  settings_json jsonb  not null default '{}'::jsonb,
  updated_at    timestamptz not null default now(),
  primary key (user_id, profile_id, platform)            -- R3 upsert target
);

-- 2. RLS + grants.
alter table public.profile_settings_blob enable row level security;
drop policy if exists "read own profile_settings_blob" on public.profile_settings_blob;
create policy "read own profile_settings_blob" on public.profile_settings_blob
  for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger on public.profile_settings_blob from anon, authenticated;
revoke select on public.profile_settings_blob from anon;
grant  select on public.profile_settings_blob to authenticated;

-- 3. Push.
create or replace function public.sync_push_profile_settings_blob_for(
  p_owner uuid, p_profile_id int, p_settings_json jsonb, p_platform text
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_owner is null or p_settings_json is null or p_platform is null then return; end if;   -- R4
  insert into public.profile_settings_blob as p (user_id, profile_id, platform, settings_json)
  values (p_owner, p_profile_id, p_platform, p_settings_json)
  on conflict (user_id, profile_id, platform) do update
    set settings_json = excluded.settings_json, updated_at = now();
end $$;

create or replace function public.sync_push_profile_settings_blob(
  p_profile_id int, p_settings_json jsonb, p_platform text
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_profile_settings_blob_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_settings_json, p_platform)
$$;

-- 4. Pull. Exact SupabaseProfileSettingsBlob shape (R7). Absent => empty set (NOT error).
create or replace function public.sync_pull_profile_settings_blob(p_profile_id int, p_platform text)
returns table(profile_id int, settings_json jsonb, updated_at timestamptz)
language sql security definer set search_path = '' as $$
  select p.profile_id, p.settings_json, p.updated_at
  from public.profile_settings_blob p
  where p.user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and p.profile_id = p_profile_id
    and p.platform = p_platform
$$;

-- 5. Function ACLs.
revoke all on function public.sync_push_profile_settings_blob_for(uuid, int, jsonb, text) from public, anon, authenticated;
revoke all     on function public.sync_push_profile_settings_blob(int, jsonb, text) from public, anon;
grant  execute on function public.sync_push_profile_settings_blob(int, jsonb, text) to authenticated;
revoke all     on function public.sync_pull_profile_settings_blob(int, text) from public, anon;
grant  execute on function public.sync_pull_profile_settings_blob(int, text) to authenticated;
