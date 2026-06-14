-- KevBox TV — library cloud restore (SNAPSHOT; no event log). Run ONCE against the KevBox Supabase
-- project (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. No secrets. Spec §5.3.

-- 1. Table -----------------------------------------------------------------------
create table if not exists public.library (
  user_id        uuid   not null references auth.users(id) on delete cascade,
  profile_id     int    not null default 1,
  content_id     text   not null,
  content_type   text   not null,
  name           text   not null default '',
  poster         text,
  poster_shape   text   not null default 'POSTER',
  background     text,
  description    text,
  release_info   text,
  imdb_rating    real,                                -- nullable float4 (client decodes Float?)
  genres         text[] not null default '{}',
  addon_base_url text,
  added_at       bigint not null default 0,
  updated_at     timestamptz not null default now(),
  primary key (user_id, profile_id, content_id)       -- R3 upsert target
);

-- 2. RLS + grants.
alter table public.library enable row level security;
drop policy if exists "read own library" on public.library;
create policy "read own library" on public.library
  for select using (auth.uid() = user_id);
revoke insert, update, delete, truncate, references, trigger on public.library from anon, authenticated;
revoke select on public.library from anon;
grant  select on public.library to authenticated;
