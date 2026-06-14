# Cloud Restore — Plan 1: Foundation + Watch Progress (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy the shared `get_sync_owner()` resolver plus the full event-sourced `watch_progress` server subsystem (2 tables + 5 RPCs) on the KevBox Supabase project, so a reinstalled/fresh KevBox TV restores its continue-watching from the cloud with **zero client changes**.

**Architecture:** Pure `auth.uid()`-scoped storage. The client already ships the sync code; we add only server objects. Every data RPC resolves the caller to `get_sync_owner() = auth.uid()` and is `SECURITY DEFINER` with an explicit owner predicate (the client sends no owner id and all members share `profile_id = 1`, so the predicate — not RLS — is the isolation boundary). Each mutating RPC is a thin JWT-resolving wrapper over an **explicit-owner inner function** (`*_for(p_owner uuid, …)`) so the logic is unit-testable. **The inner `_for` functions are EXECUTE-revoked from members** (they take the owner as an argument and would otherwise be a forge/delete side-door); the wrappers are EXECUTE-granted to `authenticated`. Watch progress is event-sourced: a state table plus an append-only `_events` log with a global monotonic `event_id`; pushes upsert with a `last_watched` guard and append an event only when the row actually changed.

**Tech Stack:** PostgreSQL 16 (Supabase), PostgREST RPC. Tests are `psql` assertion scripts (`DO $$ … ASSERT … $$`) run against a **disposable Supabase branch DB** (a faithful clone of prod, with the real `auth` schema, roles, and `member_*` triggers). Each test file runs inside a transaction that is **rolled back**, so fixtures (`auth.users` inserts, the `member_addon` seed trigger) never leak. Tool: `psql` + a branch connection string in `SYNC_TEST_DB_URL`.

**Spec:** `docs/superpowers/specs/2026-06-14-cloud-restore-design.md` (§3, §4 R1–R10, §5.1, §5.5-resolver, §9, §12). Requirement IDs (R1–R10) are referenced inline.

**Depends on:** a Supabase branch DB (see Task 1). **Produces (reused by plans 2 & 3):** `get_sync_owner_setup.sql`, `sync_test_helpers.sql`, `run_sync_tests.sh`.

> **Branch-DB testing model (read once before starting):** The branch already has the real Supabase `auth` surface, so we do **NOT** create/replace `auth.uid()`, `auth.users`, or roles. We add only two GUC helpers (`test_login`/`test_logout`) that set `request.jwt.claims` so the branch's real `auth.uid()` resolves to a chosen member. `*_setup.sql` is applied for real (persistent on the branch — that *is* the deploy-to-branch). `*_test.sql` is wrapped in `begin … rollback` so its data rolls back. Because a whole test file runs in one transaction and `set_config(..., true)` is transaction-local, **every test DO block must set its own session at entry** (`test_login(...)` or `test_logout()`) and never assume anon.

---

## File Structure

All SQL setup/teardown files live at **repo root** (matches the existing `member_*_setup.sql` convention). Test artifacts also live at repo root because `.gitignore` ignores `scripts/*`.

- `run_sync_tests.sh` — branch-DB test runner: connects via `SYNC_TEST_DB_URL`, applies `sync_test_helpers.sql`, then applies each arg — `*_setup.sql`/`*_teardown.sql` directly (`-f`), `*_test.sql` wrapped in `begin … rollback`. **Shared.**
- `sync_test_helpers.sql` — branch-safe `test_login()`/`test_logout()` GUC helpers + an assertion that the real Supabase `auth` surface exists. Does **NOT** touch `auth.uid()`/`auth.users`/roles. **Shared.**
- `get_sync_owner_setup.sql` / `get_sync_owner_teardown.sql` — the §3 resolver (teardown self-guards against dropping it while other subsystems depend on it). **Shared foundation.**
- `get_sync_owner_test.sql` — resolver assertions.
- `watch_progress_setup.sql` / `watch_progress_teardown.sql` — `watch_progress` + `watch_progress_events` tables, RLS, grants, function ACLs, and the 5 RPCs.
- `watch_progress_test.sql` — watch-progress assertions (built up additively across tasks).

> **Deploy vs test:** On the live Supabase project you run ONLY the `*_setup.sql` files (never `sync_test_helpers.sql` — those helpers are test-only). On the branch, `run_sync_tests.sh` applies the helpers, then the setup files, then the test files.

---

## Task 1: Branch-DB test helpers + runner

**Files:**
- Create: `sync_test_helpers.sql`
- Create: `run_sync_tests.sh`

- [ ] **Step 1: Write the branch-safe helpers**

Create `sync_test_helpers.sql`:

```sql
-- BRANCH-DB TEST HELPERS ONLY. Apply to a DISPOSABLE Supabase branch DB.
-- Does NOT create/replace auth.uid(), auth.users, or roles — the branch already has the real
-- Supabase surface. These two helpers set the JWT-claim GUC so the branch's real auth.uid()
-- resolves to a chosen member. Left behind only on the throwaway branch (discarded with it).

-- Fail loud if pointed at a DB that is not a real Supabase target.
do $$
begin
  assert to_regprocedure('auth.uid()') is not null, 'target must have the real auth.uid() (run against a Supabase branch)';
  assert to_regclass('auth.users') is not null, 'target must have the real auth.users';
  assert exists (select 1 from pg_roles where rolname = 'authenticated'), 'target must have the authenticated role';
end $$;

-- Simulate a logged-in member for the current transaction (set_config local=true).
create or replace function public.test_login(p_user uuid) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', p_user::text)::text, true)
$$;

create or replace function public.test_logout() returns void language sql as $$
  select set_config('request.jwt.claims', '', true)
$$;
```

- [ ] **Step 2: Write the runner script**

Create `run_sync_tests.sh`:

```bash
#!/usr/bin/env bash
# Runs SQL setup/test files against a Supabase BRANCH DB (not Docker, not the live project).
# *_setup.sql / *_teardown.sql are applied for real; *_test.sql is wrapped in a rolled-back
# transaction so its fixtures (auth.users rows, the member_addon seed trigger) never leak.
#
# Connection: export SYNC_TEST_DB_URL to the branch DIRECT endpoint (port 5432, NOT the 6543
# pooler — avoids pooler-SSL friction and session-pinning), e.g.:
#   export SYNC_TEST_DB_URL='postgresql://postgres:<pw>@db.<branch-ref>.supabase.co:5432/postgres?sslmode=require'
# (Mirror the repo's untracked .supabase_db.env convention; never commit the password.)
#
# Usage:
#   ./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"   # pin CWD = repo root so relative \i / -f resolve

: "${SYNC_TEST_DB_URL:?export SYNC_TEST_DB_URL to the branch DIRECT connection string}"
PSQL=(psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -q)

"${PSQL[@]}" -c 'select 1' >/dev/null   # fail fast on a bad connstring / SSL
"${PSQL[@]}" -f sync_test_helpers.sql

for f in "$@"; do
  echo "── applying $f"
  if [[ "$f" == *_test.sql ]]; then
    # one rolled-back transaction per test file: schema from prior *_setup.sql persists,
    # but this file's data + any DDL re-applied via \i is undone.
    printf 'begin;\n\\i %s\nrollback;\n' "$f" | "${PSQL[@]}"
  else
    "${PSQL[@]}" -f "$f"
  fi
done

echo "ALL SYNC SQL TESTS PASSED"
```

- [ ] **Step 3: Provision a branch DB, set the connection string, and smoke-test**

Create a Supabase **branch** (or a throwaway project that clones prod) and export its DIRECT connection string:
```bash
export SYNC_TEST_DB_URL='postgresql://postgres:<pw>@db.<branch-ref>.supabase.co:5432/postgres?sslmode=require'
chmod +x run_sync_tests.sh
./run_sync_tests.sh
```
Expected: connects, applies the helpers (the `auth` surface assertions pass on a real branch), prints `ALL SYNC SQL TESTS PASSED` (no test files yet). If the `auth.uid()` assertion fails, `SYNC_TEST_DB_URL` is pointing at a non-Supabase DB.

- [ ] **Step 4: Commit**

```bash
git add sync_test_helpers.sql run_sync_tests.sh
git commit -m "test(sync): add Supabase branch-DB SQL test helpers and runner"
```

---

## Task 2: `get_sync_owner()` resolver (shared foundation)

**Files:**
- Create: `get_sync_owner_setup.sql`
- Create: `get_sync_owner_teardown.sql`
- Test: `get_sync_owner_test.sql`

- [ ] **Step 1: Write the failing test**

Create `get_sync_owner_test.sql`:

```sql
-- get_sync_owner(): returns auth.uid()::text for a logged-in member; NULL for anon.
do $$
declare a uuid := '11111111-1111-1111-1111-111111111111';
        v text;
begin
  perform public.test_login(a);
  select public.get_sync_owner() into v;
  assert v = a::text, format('expected owner %s, got %s', a, v);

  perform public.test_logout();
  select public.get_sync_owner() into v;
  assert v is null, format('anon owner should be NULL, got %s', v);

  raise notice 'get_sync_owner OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_test.sql`
Expected: FAIL with `ERROR: function public.get_sync_owner() does not exist`.

- [ ] **Step 3: Write the resolver**

Create `get_sync_owner_setup.sql`:

```sql
-- KevBox TV — cloud-restore sync owner resolver. Run ONCE against the KevBox Supabase project
-- (scmqdptagksltnwiveyh). Idempotent. No secrets. Shared foundation for ALL sync subsystems.
-- KevBox is one-device-per-member, so the "sync owner" collapses to auth.uid() (spec §3).

create or replace function public.get_sync_owner()
  returns text
  language sql
  security definer
  set search_path = ''
as $$ select auth.uid()::text $$;

-- The client calls this directly as `authenticated` (AuthManager.getEffectiveUserId). Lock it
-- to authenticated only; the SECURITY DEFINER sync wrappers call it as their owner regardless.
revoke all on function public.get_sync_owner() from public, anon;
grant execute on function public.get_sync_owner() to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql get_sync_owner_test.sql`
Expected: PASS — ends with `ALL SYNC SQL TESTS PASSED`.

- [ ] **Step 5: Write the self-guarding teardown**

Create `get_sync_owner_teardown.sql`:

```sql
-- Rollback of get_sync_owner_setup.sql. SELF-GUARDING: get_sync_owner is shared — the
-- watch_progress / watched_items / library / collections / settings / profiles RPCs all call it.
-- Dropping it while any dependent sync_* function exists would make those RPCs raise 42883 at
-- runtime, which the client swallows into a SILENT restore failure. So skip the drop if dependents
-- remain. Idempotent.
do $$
declare v_deps int;
begin
  select count(*) into v_deps
  from pg_proc
  where pronamespace = 'public'::regnamespace
    and proname like 'sync\_%'
    and pg_get_functiondef(oid) ilike '%get_sync_owner()%';
  if v_deps > 0 then
    raise notice 'get_sync_owner still has % sync_* dependent(s); NOT dropping. Tear those down first.', v_deps;
  else
    drop function if exists public.get_sync_owner();
    raise notice 'get_sync_owner dropped (no dependents).';
  end if;
end $$;
select 'get_sync_owner' as obj, to_regprocedure('public.get_sync_owner()') as still_exists;
```

- [ ] **Step 6: Commit**

```bash
git add get_sync_owner_setup.sql get_sync_owner_teardown.sql get_sync_owner_test.sql
git commit -m "feat(sync): add get_sync_owner() resolver (auth.uid collapse)"
```

---

## Task 3: `watch_progress` schema (tables, RLS, grants, keys)

**Files:**
- Create: `watch_progress_setup.sql`
- Test: `watch_progress_test.sql`

- [ ] **Step 1: Write the failing schema test**

Create `watch_progress_test.sql`:

```sql
-- ============ watch_progress: schema ============
do $$
begin
  assert to_regclass('public.watch_progress') is not null, 'watch_progress table missing';
  assert to_regclass('public.watch_progress_events') is not null, 'watch_progress_events table missing';

  -- R3: composite primary key = the ON CONFLICT upsert target. Cast attname (type "name") to text.
  assert exists (
    select 1 from pg_constraint
    where conrelid = 'public.watch_progress'::regclass and contype = 'p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute
           where attrelid = 'public.watch_progress'::regclass
             and attnum = any(conkey)) = array['profile_id','progress_key','user_id']
  ), 'watch_progress PK must be (user_id, profile_id, progress_key)';

  -- RLS enabled on both tables.
  assert (select relrowsecurity from pg_class where oid = 'public.watch_progress'::regclass), 'RLS off on watch_progress';
  assert (select relrowsecurity from pg_class where oid = 'public.watch_progress_events'::regclass), 'RLS off on watch_progress_events';

  -- Writes are RPC-only: authenticated has NO direct DML on the tables.
  assert not has_table_privilege('authenticated', 'public.watch_progress', 'INSERT'), 'authenticated can INSERT watch_progress';
  assert not has_table_privilege('authenticated', 'public.watch_progress', 'UPDATE'), 'authenticated can UPDATE watch_progress';
  assert not has_table_privilege('authenticated', 'public.watch_progress', 'DELETE'), 'authenticated can DELETE watch_progress';

  raise notice 'watch_progress schema OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_test.sql`
Expected: FAIL with `watch_progress table missing` (the `ASSERT` raises).

- [ ] **Step 3: Write the schema (start `watch_progress_setup.sql`)**

Create `watch_progress_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS — `ALL SYNC SQL TESTS PASSED`.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_setup.sql watch_progress_test.sql
git commit -m "feat(sync): watch_progress + events schema, RLS, revoked grants"
```

---

## Task 4: `sync_push_watch_progress` (guarded upsert + function ACLs)

**Files:**
- Modify: `watch_progress_setup.sql` (append functions + ACLs)
- Modify: `watch_progress_test.sql` (append push tests)

- [ ] **Step 1: Append the failing push tests**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: push (R2 last_watched guard, R3 dedup) ============
do $$
declare a uuid := '22222222-2222-2222-2222-222222222222';   -- throwaway test member
        v_pos bigint; v_events int; v_events2 int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- Insert a fresh entry.
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',10,'duration',100,'last_watched',1000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 10, format('expected position 10, got %s', v_pos);

  -- Newer last_watched wins and updates position.
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',500,'duration',100,'last_watched',2000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 500, format('newer push should set position 500, got %s', v_pos);

  -- R2: a STALE push (older last_watched) must NOT clobber and must NOT append an event.
  select count(*) into v_events from public.watch_progress_events where user_id=a;
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',10,'duration',100,'last_watched',1000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 500, format('stale push must not regress; got %s', v_pos);
  assert (select count(*) from public.watch_progress_events where user_id=a) = v_events,
    'stale push must not append an event';

  -- R2 boundary: an EQUAL last_watched push also must NOT update or append (guard is ">", not ">=").
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','tt1','content_type','movie','video_id','tt1',
      'position',999,'duration',100,'last_watched',2000,'progress_key','tt1')), 1);
  select position into v_pos from public.watch_progress where user_id=a and progress_key='tt1';
  assert v_pos = 500, format('equal-timestamp push must not update; got %s', v_pos);
  assert (select count(*) from public.watch_progress_events where user_id=a) = v_events,
    'equal-timestamp push must not append an event';

  -- R3: repeated key updates the SAME row (no duplication).
  assert (select count(*) from public.watch_progress where user_id=a and progress_key='tt1') = 1,
    'progress_key must dedup to one row';

  raise notice 'watch_progress push OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: FAIL with `ERROR: function public.sync_push_watch_progress(jsonb, integer) does not exist`.

- [ ] **Step 3: Append push functions + ACLs to `watch_progress_setup.sql`**

Append to `watch_progress_setup.sql`:

```sql
-- 3. Push: explicit-owner inner fn (logic) + thin JWT-resolving wrapper.
--    R2: ON CONFLICT guarded by last_watched; append an event ONLY when the row changed.
--    R4: NULL-owner is a no-op.
create or replace function public.sync_push_watch_progress_for(
  p_owner uuid, p_profile_id int, p_entries jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; v_changed boolean;
begin
  if p_owner is null or p_entries is null then return; end if;
  for e in select value from jsonb_array_elements(p_entries) as t(value) loop
    insert into public.watch_progress as wp(
      user_id, profile_id, progress_key, content_id, content_type, video_id,
      season, episode, position, duration, last_watched)
    values (
      p_owner, p_profile_id, e->>'progress_key', e->>'content_id', e->>'content_type',
      coalesce(e->>'video_id',''),
      nullif(e->>'season','')::int, nullif(e->>'episode','')::int,
      (e->>'position')::bigint, (e->>'duration')::bigint, (e->>'last_watched')::bigint)
    on conflict (user_id, profile_id, progress_key) do update
      set content_id=excluded.content_id, content_type=excluded.content_type,
          video_id=excluded.video_id, season=excluded.season, episode=excluded.episode,
          position=excluded.position, duration=excluded.duration,
          last_watched=excluded.last_watched, updated_at=now()
      where excluded.last_watched > wp.last_watched         -- R2 guard ("strictly newer")
    returning true into v_changed;

    if v_changed then                                        -- NULL (no row) => not changed
      insert into public.watch_progress_events(
        user_id, profile_id, operation, progress_key, content_id, content_type,
        video_id, season, episode, position, duration, last_watched)
      values (p_owner, p_profile_id, 'upsert', e->>'progress_key', e->>'content_id',
        e->>'content_type', coalesce(e->>'video_id',''), nullif(e->>'season','')::int,
        nullif(e->>'episode','')::int, (e->>'position')::bigint, (e->>'duration')::bigint,
        (e->>'last_watched')::bigint);
    end if;
  end loop;
end $$;

create or replace function public.sync_push_watch_progress(
  p_entries jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_watch_progress_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_entries)
$$;

-- 3b. Function ACLs (CRITICAL — mirrors member_telemetry_setup.sql lockdown).
--     Inner _for fn takes the owner as an ARGUMENT → it must NOT be reachable by members
--     (else a member POSTs /rpc/sync_push_watch_progress_for with a victim UUID, forging data).
revoke all on function public.sync_push_watch_progress_for(uuid, int, jsonb) from public, anon, authenticated;
--     Wrapper is the only member-facing entry point.
revoke all     on function public.sync_push_watch_progress(jsonb, int) from public, anon;
grant  execute on function public.sync_push_watch_progress(jsonb, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_setup.sql watch_progress_test.sql
git commit -m "feat(sync): sync_push_watch_progress with last_watched-guarded upsert + ACLs"
```

---

## Task 5: `sync_pull_watch_progress` (owner-scoped, exact return shape)

**Files:**
- Modify: `watch_progress_setup.sql`
- Modify: `watch_progress_test.sql`

- [ ] **Step 1: Append the failing pull tests (round-trip + R1 isolation + R7 shape)**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: pull (R1 isolation, R7 exact shape, R8 stable order) ============
do $$
declare a uuid := '33333333-3333-3333-3333-333333333333';
        b uuid := '44444444-4444-4444-4444-444444444444';
        v_count int; v_keys text; v_shape text;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','m1','content_type','movie','video_id','m1',
      'position',5,'duration',50,'last_watched',1000,'progress_key','m1')), 1);

  perform public.test_login(b);
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','m2','content_type','movie','video_id','m2',
      'position',5,'duration',50,'last_watched',1000,'progress_key','m2')), 1);

  -- R1: member B (current session) pulls only B's rows, never A's.
  select count(*), string_agg(progress_key, ',' order by progress_key)
    into v_count, v_keys
    from public.sync_pull_watch_progress(1, null, null);
  assert v_count = 1, format('B should pull 1 row, got %s', v_count);
  assert v_keys = 'm2', format('B must not see A''s rows; got keys %s', v_keys);

  -- R7 wire-shape guard: a pulled row, as JSON, must have EXACTLY the 11 client-model keys —
  -- no updated_at, no extra columns. LIMIT the ROW first, THEN expand keys (key-expansion is
  -- set-returning, so limiting after it would truncate to a single key).
  select string_agg(k, ',' order by k) into v_shape
  from (
    select jsonb_object_keys(to_jsonb(t)) as k
    from ( select * from public.sync_pull_watch_progress(1, null, null) limit 1 ) t
  ) s;
  assert v_shape = 'content_id,content_type,duration,episode,last_watched,position,profile_id,progress_key,season,user_id,video_id',
    format('pull row JSON keys must match SupabaseWatchProgress exactly; got: %s', v_shape);

  raise notice 'watch_progress pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: FAIL with `ERROR: function public.sync_pull_watch_progress(...) does not exist`.

- [ ] **Step 3: Append the pull function (+ grant)**

Append to `watch_progress_setup.sql`:

```sql
-- 4. Pull snapshot. SECURITY DEFINER + explicit owner predicate (R1) — the client sends no owner id.
--    Explicit RETURNS TABLE matching SupabaseWatchProgress EXACTLY (R7): no updated_at, no extra keys,
--    so decode never hits an unknown column. "position" is a reserved word — quote it in the column
--    list (the output JSON key is still literally "position", matching the Kotlin field). user_id cast
--    to text to match the Kotlin String field. R8: total-order tiebreaker so any limited page is stable.
create or replace function public.sync_pull_watch_progress(
  p_profile_id int, p_since_last_watched bigint default null, p_limit int default null
) returns table(
  user_id text, content_id text, content_type text, video_id text,
  season int, episode int, "position" bigint, duration bigint,
  last_watched bigint, progress_key text, profile_id int
) language sql security definer set search_path = '' as $$
  select wp.user_id::text, wp.content_id, wp.content_type, wp.video_id,
         wp.season, wp.episode, wp.position, wp.duration,
         wp.last_watched, wp.progress_key, wp.profile_id
  from public.watch_progress wp
  where wp.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and wp.profile_id = p_profile_id
    and (p_since_last_watched is null or wp.last_watched >= p_since_last_watched)
  order by wp.last_watched desc, wp.progress_key asc                -- R8 total order
  limit p_limit                                                     -- NULL => all rows
$$;
revoke all     on function public.sync_pull_watch_progress(int, bigint, int) from public, anon;
grant  execute on function public.sync_pull_watch_progress(int, bigint, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_setup.sql watch_progress_test.sql
git commit -m "feat(sync): sync_pull_watch_progress (owner-scoped, exact shape, stable order)"
```

---

## Task 6: `sync_get_watch_progress_delta_cursor` (coalesce to 0)

**Files:**
- Modify: `watch_progress_setup.sql`
- Modify: `watch_progress_test.sql`

- [ ] **Step 1: Append the failing cursor test**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: delta cursor (R5 coalesce, never NULL) ============
do $$
declare z uuid := '55555555-5555-5555-5555-555555555555';
        c1 bigint; c2 bigint; v_max bigint;
begin
  insert into auth.users(id) values (z) on conflict do nothing;
  perform public.test_login(z);

  -- Brand-new member, zero events: MUST return 0 (not NULL — client decodes a non-null Long).
  select public.sync_get_watch_progress_delta_cursor(1) into c1;
  assert c1 = 0, format('empty cursor must be 0, got %s', c1);

  -- After a push, the cursor equals this owner's own max event_id (not a global/foreign value).
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object(
      'content_id','c','content_type','movie','video_id','c',
      'position',1,'duration',10,'last_watched',1,'progress_key','c')), 1);
  select public.sync_get_watch_progress_delta_cursor(1) into c2;
  select max(event_id) into v_max from public.watch_progress_events where user_id=z;
  assert c2 = v_max, format('cursor must equal owner max event_id %s, got %s', v_max, c2);

  raise notice 'watch_progress delta cursor OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: FAIL with `function public.sync_get_watch_progress_delta_cursor(integer) does not exist`.

- [ ] **Step 3: Append the cursor function (+ grant)**

Append to `watch_progress_setup.sql`:

```sql
-- 5. Delta cursor: latest event_id for THIS owner, COALESCE'd to 0 (R5) — the client decodes a
--    non-null Long, and watched-items (plan 2) has no client-side fallback, so a NULL would crash.
create or replace function public.sync_get_watch_progress_delta_cursor(p_profile_id int)
  returns bigint language sql security definer set search_path = '' as $$
  select coalesce(max(event_id), 0)
  from public.watch_progress_events
  where user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and profile_id = p_profile_id
$$;
revoke all     on function public.sync_get_watch_progress_delta_cursor(int) from public, anon;
grant  execute on function public.sync_get_watch_progress_delta_cursor(int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_setup.sql watch_progress_test.sql
git commit -m "feat(sync): sync_get_watch_progress_delta_cursor (coalesce to 0)"
```

---

## Task 7: `sync_pull_watch_progress_delta` (owner-scoped, ascending, limited)

**Files:**
- Modify: `watch_progress_setup.sql`
- Modify: `watch_progress_test.sql`

- [ ] **Step 1: Append the failing delta-pull test**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: delta pull (R1 owner, R8 asc+limit) ============
do $$
declare d uuid := '66666666-6666-6666-6666-666666666666';
        e uuid := '77777777-7777-7777-7777-777777777777';
        n int; first_id bigint; last_id bigint;
begin
  insert into auth.users(id) values (d),(e) on conflict do nothing;

  perform public.test_login(e);   -- noise from another member must never appear in d's delta
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','x','content_type','movie',
      'video_id','x','position',1,'duration',9,'last_watched',1,'progress_key','x')), 1);

  perform public.test_login(d);
  for i in 1..3 loop
    perform public.sync_push_watch_progress(
      jsonb_build_array(jsonb_build_object('content_id','k'||i,'content_type','movie',
        'video_id','k'||i,'position',i,'duration',99,'last_watched',i,'progress_key','k'||i)), 1);
  end loop;

  -- From cursor 0, limit 2: exactly 2 of d's events, ascending, none of e's.
  select count(*), min(event_id), max(event_id)
    into n, first_id, last_id
    from public.sync_pull_watch_progress_delta(1, 0, 2);
  assert n = 2, format('expected 2 delta rows, got %s', n);
  assert first_id < last_id, 'delta rows must be ascending by event_id';
  assert not exists (
    select 1 from public.sync_pull_watch_progress_delta(1, 0, 100) where progress_key = 'x'
  ), 'd must never see member e''s events';

  raise notice 'watch_progress delta pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: FAIL with `function public.sync_pull_watch_progress_delta(...) does not exist`.

- [ ] **Step 3: Append the delta-pull function (+ grant)**

Append to `watch_progress_setup.sql`:

```sql
-- 6. Delta pull: owner-scoped (R1), event_id > cursor, ASC, limited (R8). Exact event shape (R7);
--    "position" quoted (reserved word) — output JSON key stays "position".
create or replace function public.sync_pull_watch_progress_delta(
  p_profile_id int, p_since_event_id bigint, p_limit int
) returns table(
  event_id bigint, operation text, progress_key text, content_id text, content_type text,
  video_id text, season int, episode int, "position" bigint, duration bigint, last_watched bigint
) language sql security definer set search_path = '' as $$
  select ev.event_id, ev.operation, ev.progress_key, ev.content_id, ev.content_type,
         ev.video_id, ev.season, ev.episode, ev.position, ev.duration, ev.last_watched
  from public.watch_progress_events ev
  where ev.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and ev.profile_id = p_profile_id
    and ev.event_id > p_since_event_id
  order by ev.event_id asc                                          -- R8
  limit p_limit
$$;
revoke all     on function public.sync_pull_watch_progress_delta(int, bigint, int) from public, anon;
grant  execute on function public.sync_pull_watch_progress_delta(int, bigint, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_setup.sql watch_progress_test.sql
git commit -m "feat(sync): sync_pull_watch_progress_delta (owner-scoped, ascending)"
```

---

## Task 8: `sync_delete_watch_progress` (string keys + delete events)

**Files:**
- Modify: `watch_progress_setup.sql`
- Modify: `watch_progress_test.sql`

- [ ] **Step 1: Append the failing delete test**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: delete (R6 string-key array; delete events; no resurrection) ============
do $$
declare g uuid := '88888888-8888-8888-8888-888888888888';
        n int; del_events int;
begin
  insert into auth.users(id) values (g) on conflict do nothing;
  perform public.test_login(g);

  perform public.sync_push_watch_progress(
    jsonb_build_array(
      jsonb_build_object('content_id','d1','content_type','movie','video_id','d1',
        'position',1,'duration',9,'last_watched',1,'progress_key','d1'),
      jsonb_build_object('content_id','d2','content_type','movie','video_id','d2',
        'position',1,'duration',9,'last_watched',1,'progress_key','d2')), 1);

  -- p_keys is an array of PLAIN STRINGS (not objects) for watch_progress (R6).
  perform public.sync_delete_watch_progress(jsonb_build_array('d1'), 1);

  select count(*) into n from public.watch_progress where user_id=g;
  assert n = 1, format('after delete, expected 1 row, got %s', n);
  assert not exists (select 1 from public.watch_progress where user_id=g and progress_key='d1'),
    'deleted key must be gone';

  -- A delete event is appended so other devices converge (and the row does not resurrect on pull).
  select count(*) into del_events
    from public.watch_progress_events where user_id=g and operation='delete' and progress_key='d1';
  assert del_events = 1, format('expected 1 delete event, got %s', del_events);
  assert not exists (
    select 1 from public.sync_pull_watch_progress(1, null, null) where progress_key='d1'
  ), 'deleted key must not reappear in a pull';

  raise notice 'watch_progress delete OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: FAIL with `function public.sync_delete_watch_progress(...) does not exist`.

- [ ] **Step 3: Append the delete functions (+ ACLs)**

Append to `watch_progress_setup.sql`:

```sql
-- 7. Delete: p_keys = array of plain progress_key strings (R6). Append a delete event per removed row.
create or replace function public.sync_delete_watch_progress_for(
  p_owner uuid, p_profile_id int, p_keys jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare k text;
begin
  if p_owner is null or p_keys is null then return; end if;     -- R4
  for k in select value from jsonb_array_elements_text(p_keys) as t(value) loop
    delete from public.watch_progress
      where user_id = p_owner and profile_id = p_profile_id and progress_key = k;
    if found then
      insert into public.watch_progress_events(user_id, profile_id, operation, progress_key)
        values (p_owner, p_profile_id, 'delete', k);
    end if;
  end loop;
end $$;

create or replace function public.sync_delete_watch_progress(
  p_keys jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_delete_watch_progress_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_keys)
$$;

-- 7b. Function ACLs: lock the inner _for fn, expose only the wrapper to members.
revoke all on function public.sync_delete_watch_progress_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_delete_watch_progress(jsonb, int) from public, anon;
grant  execute on function public.sync_delete_watch_progress(jsonb, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_setup.sql watch_progress_test.sql
git commit -m "feat(sync): sync_delete_watch_progress (string keys + delete events + ACLs)"
```

---

## Task 9: Function-ACL + NULL-owner safety (R1 side-door + R4)

**Files:**
- Modify: `watch_progress_test.sql`

(No setup change — this proves the ACLs and NULL-owner guards already added hold. The `authenticated` role exists on the branch DB, so `has_function_privilege` is meaningful here.)

- [ ] **Step 1: Append the ACL + NULL-owner test**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: function ACLs (MF-2/MF-3) ============
do $$
begin
  -- Inner _for fns must NOT be callable by members (else owner-arg forgery bypasses R1).
  assert not has_function_privilege('authenticated',
    'public.sync_push_watch_progress_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert not has_function_privilege('authenticated',
    'public.sync_delete_watch_progress_for(uuid,int,jsonb)', 'EXECUTE'),
    'delete _for must NOT be executable by authenticated';

  -- The 5 client-facing wrappers MUST be callable by members.
  assert has_function_privilege('authenticated', 'public.sync_push_watch_progress(jsonb,int)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watch_progress(int,bigint,int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_get_watch_progress_delta_cursor(int)', 'EXECUTE'),
    'cursor wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watch_progress_delta(int,bigint,int)', 'EXECUTE'),
    'delta wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_delete_watch_progress(jsonb,int)', 'EXECUTE'),
    'delete wrapper must be executable by authenticated';

  raise notice 'watch_progress ACLs OK';
end $$;

-- ============ watch_progress: NULL-owner safety (R4) ============
do $$
declare before_rows int; after_rows int; cur bigint;
begin
  perform public.test_logout();   -- no JWT => get_sync_owner() is NULL

  select count(*) into before_rows from public.watch_progress;

  -- Push with no session writes NOTHING (no NULL-user_id rows, no exception).
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','n','content_type','movie',
      'video_id','n','position',1,'duration',9,'last_watched',1,'progress_key','n')), 1);
  -- Delete with no session is a no-op.
  perform public.sync_delete_watch_progress(jsonb_build_array('n'), 1);

  select count(*) into after_rows from public.watch_progress;
  assert after_rows = before_rows, 'anon push/delete must not change row count';
  assert not exists (select 1 from public.watch_progress where user_id is null), 'no NULL-user_id rows';

  -- Anon pull/cursor return empty/0, never error.
  assert (select count(*) from public.sync_pull_watch_progress(1, null, null)) = 0, 'anon pull must be empty';
  select public.sync_get_watch_progress_delta_cursor(1) into cur;
  assert cur = 0, format('anon cursor must be 0, got %s', cur);

  raise notice 'watch_progress NULL-owner OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes (guards/ACLs already exist)**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS. (If an ACL assert FAILS, a `revoke`/`grant` line is missing from the matching Task 4/5/6/7/8 step — add it before continuing. If a NULL-owner assert FAILS, a wrapper is missing its `nullif(get_sync_owner(),'')::uuid` cast or an inner fn its `if p_owner is null` guard.)

- [ ] **Step 3: Commit**

```bash
git add watch_progress_test.sql
git commit -m "test(sync): assert watch_progress function ACLs + NULL-owner safety"
```

---

## Task 10: Teardown + idempotency (data-preserving)

**Files:**
- Create: `watch_progress_teardown.sql`
- Modify: `watch_progress_test.sql`

- [ ] **Step 1: Append the idempotency test (proves re-apply preserves data, not just the table)**

Append to `watch_progress_test.sql`:

```sql
-- ============ watch_progress: idempotent re-apply preserves data ============
do $$
declare s uuid := '99999999-9999-9999-9999-999999999999';
        v_rows int; v_events int;
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_watch_progress(
    jsonb_build_array(jsonb_build_object('content_id','sent','content_type','movie',
      'video_id','sent','position',7,'duration',70,'last_watched',7,'progress_key','idemp_sentinel')), 1);
end $$;

\i watch_progress_setup.sql   -- re-apply the whole setup mid-test (create-if-not-exists / or-replace)

do $$
declare s uuid := '99999999-9999-9999-9999-999999999999';
begin
  perform public.test_login(s);   -- re-establish session (set_config is txn-local; new block)
  assert to_regclass('public.watch_progress') is not null, 're-apply dropped the table';
  assert (select count(*) from public.watch_progress where user_id=s and progress_key='idemp_sentinel') = 1,
    're-applying setup must PRESERVE existing rows (no drop-then-create)';
  assert (select count(*) from public.watch_progress_events where user_id=s and progress_key='idemp_sentinel') = 1,
    're-applying setup must preserve existing events';
  raise notice 'watch_progress idempotency OK';
end $$;
```

> The mid-test `\i watch_progress_setup.sql` runs inside this test file's transaction; on rollback (runner-level) it is undone, so it never affects the persisted branch schema. A sentinel row written *before* the re-apply proves `create … if not exists` / `create or replace` preserve data (R9).

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql`
Expected: PASS.

- [ ] **Step 3: Write the teardown**

Create `watch_progress_teardown.sql`:

```sql
-- Rollback of watch_progress_setup.sql. Removes ONLY watch_progress objects. Does NOT touch
-- get_sync_owner (shared), auth.users, member_*, or telemetry. DROPS the stored progress data.
-- Idempotent. Mirrors member_telemetry_teardown.sql.

-- 1. Functions (wrappers + inner fns).
drop function if exists public.sync_push_watch_progress(jsonb, int);
drop function if exists public.sync_push_watch_progress_for(uuid, int, jsonb);
drop function if exists public.sync_pull_watch_progress(int, bigint, int);
drop function if exists public.sync_get_watch_progress_delta_cursor(int);
drop function if exists public.sync_pull_watch_progress_delta(int, bigint, int);
drop function if exists public.sync_delete_watch_progress(jsonb, int);
drop function if exists public.sync_delete_watch_progress_for(uuid, int, jsonb);

-- 2. Tables (RLS policies + indexes drop with the table).
drop table if exists public.watch_progress_events;
drop table if exists public.watch_progress;

-- Sanity: both should report NULL (gone).
select 'watch_progress' as obj, to_regclass('public.watch_progress') as still_exists
union all select 'watch_progress_events', to_regclass('public.watch_progress_events');
```

- [ ] **Step 4: Verify teardown drops cleanly**

Run:
```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_teardown.sql
```
Expected: PASS — teardown applies without error; the final sanity `select` shows both objects as `NULL`. (`run_sync_tests.sh` exits non-zero only on an error, not on a sanity-select result, so a clean apply = pass.)

> Note: this leaves the branch without `watch_progress` objects. Re-run a full suite (Task 11) afterward to restore them on the branch, or recreate the branch.

- [ ] **Step 5: Commit**

```bash
git add watch_progress_teardown.sql watch_progress_test.sql
git commit -m "feat(sync): watch_progress teardown + data-preserving idempotency test"
```

---

## Task 11: Full-suite green + contract cross-check + go-live gate

**Files:** none (verification only).

- [ ] **Step 1: Run the complete suite from scratch on a fresh branch**

Run:
```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql
```
Expected: PASS — `ALL SYNC SQL TESTS PASSED`, covering: schema/keys/RLS/grants, push guard+dedup+equal-ts, pull isolation+shape+order, cursor coalesce+exact-max, delta asc+limit+isolation, delete+no-resurrection, function ACLs, NULL-owner safety, data-preserving idempotency.

- [ ] **Step 2: Confirm the RPC contract matches the client call sites**

Manually diff against the client (no code change — a contract check):
- `sync_push_watch_progress(p_entries jsonb, p_profile_id int)` ↔ `WatchProgressSyncService.kt:174-194`/`218-235`
- `sync_pull_watch_progress(p_profile_id int, p_since_last_watched bigint, p_limit int)` ↔ `:267-277`
- `sync_get_watch_progress_delta_cursor(p_profile_id int)` ↔ `:95-99`
- `sync_pull_watch_progress_delta(p_profile_id int, p_since_event_id bigint, p_limit int)` ↔ `:107-113`
- `sync_delete_watch_progress(p_keys jsonb, p_profile_id int)` ↔ `:136-143`

Expected: every function name and **argument-name set** matches exactly (PostgREST binds by name).

- [ ] **Step 3: Record the go-live gate**

This plan delivers and validates `watch_progress` **on a branch**. Per spec §12, do **NOT** apply `watch_progress_setup.sql` (or `get_sync_owner_setup.sql`) to the **live** project fleet-wide until plan 3 ships the §9 detection probe, the §8 canary path, and the §10 runbook (the only rollback today is data-destructive teardown). Branch + a single canary build is allowed now. No commit — this is a checklist gate.

---

## Self-Review (author checklist — completed at write time)

**1. Spec coverage (Plan 1 scope = §3, §5.1, §5.5-resolver, applicable R1–R10):**
- §3 `get_sync_owner` collapse → Task 2 (+ self-guarding teardown). ✓
- §5.1 tables + 5 RPCs → Tasks 3–8. ✓
- R1 owner predicate on every read + write/delete → Tasks 5/6/7/8 (+ T-ISO in 5 & 7). ✓
- R2 last_watched guard incl. equal-timestamp boundary → Task 4. ✓ · R3 PK/upsert target → Task 3 + Task 4. ✓
- R4 NULL-owner → Task 9. ✓ · R5 coalesce cursor + exact-max → Task 6. ✓
- R7 exact wire-shape (LIMIT-before-expand) + event non-null defaults → Tasks 5 & 3. ✓
- R8 delta asc+limit AND snapshot total-order tiebreaker → Tasks 7 & 5. ✓ · R9 idempotent (data-preserving) setup / scoped teardown → Task 10. ✓
- **Function ACLs (MF-2/MF-3):** inner `_for` revoked, wrappers granted to `authenticated`, asserted → Tasks 4/5/6/7/8 + Task 9. ✓
- R6 (two delete shapes) — watch_progress half (string keys) → Task 8. *Watched-items object-key half is Plan 2.*
- R10 retention (`prune_sync_events`) — **deferred to Plan 3** (shared across all `*_events` tables). Noted.
- §8 gating / §9 cross-cutting tests (T-TRAKT, T-REG, T-E2E, probe, runbook, canary) — **Plan 3**; go-live gate recorded in Task 11.

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"; every code step contains complete SQL. ✓

**3. Type consistency:** Inner fns `*_for(p_owner uuid, p_profile_id int, …)`; wrappers `(p_entries jsonb, p_profile_id int)` / `(p_keys jsonb, p_profile_id int)`; teardown drops the exact signatures created; ACL revoke/grant signatures match. `event_id bigint`, `position/duration/last_watched bigint`, `season/episode int` consistent across state table, event table, pull, and delta. `"position"` quoted in both `RETURNS TABLE` lists; the output key stays `position`. ✓

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-14-cloud-restore-1-foundation-watch-progress.md`. Prerequisite: a disposable Supabase **branch DB** with its DIRECT connection string in `SYNC_TEST_DB_URL` (Task 1). Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.
