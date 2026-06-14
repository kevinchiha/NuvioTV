-- KevBox TV — home-catalog settings cloud restore (SNAPSHOT JSON blob; per-platform). Run ONCE AFTER
-- get_sync_owner_setup.sql. Idempotent. Spec §5.4. settings_json is a JSON OBJECT stored verbatim.

-- 1. Table (keyed per platform — client pulls home_catalog_shared / tv / mobile).
create table if not exists public.home_catalog_settings (
  user_id       uuid   not null references auth.users(id) on delete cascade,
  profile_id    int    not null default 1,
  platform      text   not null,
  settings_json jsonb  not null default '{}'::jsonb,
  updated_at    timestamptz not null default now(),
  primary key (user_id, profile_id, platform)            -- R3 upsert target
);

-- 2. RLS + grants.
alter table public.home_catalog_settings enable row level security;
drop policy if exists "read own home_catalog_settings" on public.home_catalog_settings;
create policy "read own home_catalog_settings" on public.home_catalog_settings
  for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger on public.home_catalog_settings from anon, authenticated;
revoke select on public.home_catalog_settings from anon;
grant  select on public.home_catalog_settings to authenticated;

-- 3. Push: snapshot upsert per (user, profile, platform). R4 NULL-owner no-op.
create or replace function public.sync_push_home_catalog_settings_for(
  p_owner uuid, p_profile_id int, p_settings_json jsonb, p_platform text
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_owner is null or p_settings_json is null or p_platform is null then return; end if;   -- R4
  insert into public.home_catalog_settings as h (user_id, profile_id, platform, settings_json)
  values (p_owner, p_profile_id, p_platform, p_settings_json)
  on conflict (user_id, profile_id, platform) do update
    set settings_json = excluded.settings_json, updated_at = now();
end $$;

create or replace function public.sync_push_home_catalog_settings(
  p_profile_id int, p_settings_json jsonb, p_platform text
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_home_catalog_settings_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_settings_json, p_platform)
$$;

-- 4. Pull. Owner-scoped (R1), per-platform. Exact SupabaseHomeCatalogSettingsBlob shape (R7):
--    profile_id, settings_json, updated_at. Absent platform => empty set (NOT error).
create or replace function public.sync_pull_home_catalog_settings(p_profile_id int, p_platform text)
returns table(profile_id int, settings_json jsonb, updated_at timestamptz)
language sql security definer set search_path = '' as $$
  select h.profile_id, h.settings_json, h.updated_at
  from public.home_catalog_settings h
  where h.user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and h.profile_id = p_profile_id
    and h.platform = p_platform
$$;

-- 5. Function ACLs.
revoke all on function public.sync_push_home_catalog_settings_for(uuid, int, jsonb, text) from public, anon, authenticated;
revoke all     on function public.sync_push_home_catalog_settings(int, jsonb, text) from public, anon;
grant  execute on function public.sync_push_home_catalog_settings(int, jsonb, text) to authenticated;
revoke all     on function public.sync_pull_home_catalog_settings(int, text) from public, anon;
grant  execute on function public.sync_pull_home_catalog_settings(int, text) to authenticated;
