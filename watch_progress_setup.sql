-- KevBox TV — watch_progress cloud restore. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh) AFTER get_sync_owner_setup.sql. Idempotent. No secrets.
-- Event-sourced: watch_progress (state) + watch_progress_events (append-only log). Spec §5.1, R1-R8.

-- 1. Tables -----------------------------------------------------------------------
create table if not exists public.watch_progress (
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  progress_key text   not null,
  content_id   text   not null,
  content_type text   not null,
  video_id     text   not null default '',
  season       int,
  episode      int,
  position     bigint not null,
  duration     bigint not null,
  last_watched bigint not null,                       -- epoch ms
  updated_at   timestamptz not null default now(),
  primary key (user_id, profile_id, progress_key)     -- R3: upsert target
);

create table if not exists public.watch_progress_events (
  event_id     bigint generated always as identity primary key,  -- global monotonic cursor
  user_id      uuid   not null references auth.users(id) on delete cascade,
  profile_id   int    not null default 1,
  operation    text   not null check (operation in ('upsert','delete')),
  progress_key text   not null,
  content_id   text   not null default '',
  content_type text   not null default '',
  video_id     text   not null default '',
  season       int,
  episode      int,
  position     bigint not null default 0,             -- R7: non-null even on delete events
  duration     bigint not null default 0,
  last_watched bigint not null default 0,
  created_at   timestamptz not null default now()
);
create index if not exists watch_progress_events_owner_idx
  on public.watch_progress_events (user_id, profile_id, event_id);

-- 2. RLS: read own rows only. Writes go through SECURITY DEFINER RPCs (below).
alter table public.watch_progress        enable row level security;
alter table public.watch_progress_events enable row level security;
drop policy if exists "read own watch_progress" on public.watch_progress;
create policy "read own watch_progress" on public.watch_progress
  for select using (auth.uid() = user_id);
drop policy if exists "read own watch_progress_events" on public.watch_progress_events;
create policy "read own watch_progress_events" on public.watch_progress_events
  for select using (auth.uid() = user_id);

-- 2b. Revoke default DML grants (mirrors member_addon_setup.sql). Writes are RPC-only.
revoke insert, update, delete, truncate, references, trigger
  on public.watch_progress, public.watch_progress_events from anon, authenticated;
-- 2c. SELECT stays for authenticated but is RLS-scoped to own rows (defense-in-depth alongside the
--     owner-scoped pull RPC); anon gets nothing. Matches the member_addon read-own posture and makes
--     the read-own policy testable (Task 9, D).
revoke select on public.watch_progress, public.watch_progress_events from anon;
grant  select on public.watch_progress, public.watch_progress_events to authenticated;
