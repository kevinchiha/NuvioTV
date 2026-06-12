-- KevBox member enrollment — PRODUCTION setup.
-- Run ONCE as POSTGRES in the Supabase SQL editor (kevbox_admin cannot CREATE TABLE/GRANT).
-- Prefer: psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 -f deploy/kevbox_member_setup.sql
-- Table DDL is byte-identical to packages/core/test/schema.sql (M2 drift guard).

-- Guard: the grants below target role kevbox_admin; fail loudly if it doesn't exist.
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'kevbox_admin') then
    raise exception 'role kevbox_admin does not exist — create it before running this setup';
  end if;
end
$$;

create table if not exists public.kevbox_member (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  aiostreams_name    text not null
                       check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  premiumize_key_enc text,
  enrolled           boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create unique index if not exists kevbox_member_name_active
  on public.kevbox_member (aiostreams_name) where enrolled;

create table if not exists public.kevbox_allowlist_extra (
  aiostreams_name text primary key
                    check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  note            text,
  created_at      timestamptz not null default now()
);

-- Defense in depth: these sidecar tables hold encrypted keys + allowlist names. Even though
-- kevbox_admin is BYPASSRLS, enable RLS and strip the Supabase default-grants to anon/authenticated
-- so a public view or a stray PostgREST request can never read them (mirrors member_access).
alter table public.kevbox_member enable row level security;
alter table public.kevbox_allowlist_extra enable row level security;
revoke all on public.kevbox_member from anon, authenticated;
revoke all on public.kevbox_allowlist_extra from anon, authenticated;

-- The dashboard connects as the least-privileged kevbox_admin role (deploy/env.example).
grant select, insert, update, delete on public.kevbox_member to kevbox_admin;
grant select, insert, update, delete on public.kevbox_allowlist_extra to kevbox_admin;
-- Advisory locks need no table grant. kevbox_admin is BYPASSRLS (like member_access).
