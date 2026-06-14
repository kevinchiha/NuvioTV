-- KevBox TV — watched_items cloud restore. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. No secrets.
-- Event-sourced: watched_items (state) + watched_items_events (append-only log). Spec §5.2, R1-R8.
-- REQUIRES PostgreSQL 15+ for `unique nulls not distinct` (branch + prod are PG17).

-- 1. Tables -----------------------------------------------------------------------
create table if not exists public.watched_items (
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  content_id   text   not null,
  content_type text   not null,
  title        text   not null default '',
  season       int,
  episode      int,
  watched_at   bigint not null,                       -- epoch ms
  updated_at   timestamptz not null default now(),
  -- R3/R6: NULL-aware composite unique key = the ON CONFLICT upsert target. Movies push
  -- season/episode as SQL NULL; `nulls not distinct` makes two movie rows with the same
  -- content_id collide (the default NULLS DISTINCT would duplicate them forever).
  unique nulls not distinct (user_id, profile_id, content_id, season, episode)
);

create table if not exists public.watched_items_events (
  event_id     bigint generated always as identity primary key,  -- global monotonic cursor
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  operation    text   not null check (operation in ('upsert','delete')),
  content_id   text   not null,
  content_type text   not null default '',
  title        text   not null default '',
  season       int,
  episode      int,
  watched_at   bigint not null default 0,             -- R7: zeroed on delete events
  created_at   timestamptz not null default now()
);
create index if not exists watched_items_events_owner_idx
  on public.watched_items_events (user_id, profile_id, event_id);

-- 2. RLS: read own rows only. Writes go through SECURITY DEFINER RPCs (below).
alter table public.watched_items        enable row level security;
alter table public.watched_items_events enable row level security;
drop policy if exists "read own watched_items" on public.watched_items;
create policy "read own watched_items" on public.watched_items
  for select using (auth.uid() = user_id);
drop policy if exists "read own watched_items_events" on public.watched_items_events;
create policy "read own watched_items_events" on public.watched_items_events
  for select using (auth.uid() = user_id);

-- 2b. Revoke default DML; writes are RPC-only. SELECT stays for authenticated (RLS-scoped); anon none.
revoke insert, update, delete, truncate, references, trigger
  on public.watched_items, public.watched_items_events from anon, authenticated;
revoke select on public.watched_items, public.watched_items_events from anon;
grant  select on public.watched_items, public.watched_items_events to authenticated;
