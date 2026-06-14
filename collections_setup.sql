-- KevBox TV — collections cloud restore (SNAPSHOT JSON blob; no event log). Run ONCE against the
-- KevBox Supabase project (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. Spec §5.4.

-- 1. Table -----------------------------------------------------------------------
create table if not exists public.collections (
  user_id          uuid   not null references auth.users(id) on delete cascade,
  profile_id       int    not null default 1,
  collections_json jsonb  not null default '[]'::jsonb,    -- ROOT IS A JSON ARRAY (verbatim; stringify breaks decode)
  updated_at       timestamptz not null default now(),
  primary key (user_id, profile_id)                        -- R3 upsert target
);

-- 2. RLS + grants.
alter table public.collections enable row level security;
drop policy if exists "read own collections" on public.collections;
create policy "read own collections" on public.collections
  for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger on public.collections from anon, authenticated;
revoke select on public.collections from anon;
grant  select on public.collections to authenticated;

-- 3. Push: snapshot upsert (last-write-wins). p_profile_id FIRST. R4 NULL-owner no-op.
create or replace function public.sync_push_collections_for(
  p_owner uuid, p_profile_id int, p_collections_json jsonb
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_owner is null or p_collections_json is null then return; end if;   -- R4
  insert into public.collections as c (user_id, profile_id, collections_json)
  values (p_owner, p_profile_id, p_collections_json)
  on conflict (user_id, profile_id) do update
    set collections_json = excluded.collections_json, updated_at = now();
end $$;

create or replace function public.sync_push_collections(
  p_profile_id int, p_collections_json jsonb
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_collections_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_collections_json)
$$;

-- 4. Pull snapshot. Owner-scoped (R1). Exact SupabaseCollectionBlob shape (R7): profile_id,
--    collections_json, updated_at (no user_id). At most one row (PK); client takes firstOrNull.
create or replace function public.sync_pull_collections(p_profile_id int)
returns table(profile_id int, collections_json jsonb, updated_at timestamptz)
language sql security definer set search_path = '' as $$
  select c.profile_id, c.collections_json, c.updated_at
  from public.collections c
  where c.user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and c.profile_id = p_profile_id
$$;

-- 5. Function ACLs (inner _for revoked; wrappers granted to authenticated).
revoke all on function public.sync_push_collections_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_collections(int, jsonb) from public, anon;
grant  execute on function public.sync_push_collections(int, jsonb) to authenticated;
revoke all     on function public.sync_pull_collections(int) from public, anon;
grant  execute on function public.sync_pull_collections(int) to authenticated;
