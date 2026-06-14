# Cloud Restore — Plan 2: Watched Items + Library (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy the event-sourced `watched_items` subsystem (2 tables + 5 RPCs) and the snapshot `library` subsystem (1 table + 2 RPCs) on the KevBox Supabase project, so a reinstalled/fresh KevBox TV restores its watched-history and saved-library from the cloud with **zero client changes**.

**Architecture:** Identical pattern to Plan 1 (`watch_progress`) — pure `auth.uid()`-scoped storage, each data RPC resolves the caller via the shared `get_sync_owner()` (already deployed) and is `SECURITY DEFINER` with an explicit owner predicate; mutating RPCs are thin JWT-resolving wrappers over EXECUTE-revoked explicit-owner inner `*_for(p_owner uuid, …)` functions. Two subsystem-specific twists: (1) **watched_items has a NULL-aware composite unique key** `(user_id, profile_id, content_id, season, episode)` declared `UNIQUE NULLS NOT DISTINCT` so movies (which push `season`/`episode` as SQL NULL) dedupe instead of duplicating forever; deletes match with `IS NOT DISTINCT FROM`. (2) **library is a snapshot** — no event log, plain last-write-wins upsert; `genres` is `text[]`, `imdb_rating` is a nullable `real`.

**Tech Stack:** PostgreSQL **15+** (Supabase; branch + prod are PG17 — `UNIQUE NULLS NOT DISTINCT` requires PG15+), PostgREST RPC. Tests are `psql` assertion scripts run against the **disposable Supabase branch DB** already provisioned for Plan 1 (`SYNC_TEST_DB_URL` in the gitignored `.supabase_db.env`, session-mode pooler). Reuses the Plan-1 harness verbatim: `run_sync_tests.sh` + `sync_test_helpers.sql` (`test_login`/`test_logout`), each `*_test.sql` wrapped in `begin … rollback`.

**Spec:** `docs/superpowers/specs/2026-06-14-cloud-restore-design.md` (§4 R1–R10, §5.2, §5.3). Requirement IDs (R1–R10) referenced inline.

**Depends on:** Plan 1 (deployed + green on the branch): `get_sync_owner_setup.sql`, `sync_test_helpers.sql`, `run_sync_tests.sh`. **Verified client contract** (read 2026-06-14 from `WatchedItemsSyncService.kt`, `LibrarySyncService.kt`, `SupabaseModels.kt`):

| RPC | SQL signature (PostgREST binds by name) | Decode target |
|---|---|---|
| `sync_push_watched_items` | `(p_items jsonb, p_profile_id int)` | — |
| `sync_pull_watched_items` | `(p_profile_id int, p_page int, p_page_size int)` | `decodeList<SupabaseWatchedItem>` |
| `sync_get_watched_items_delta_cursor` | `(p_profile_id int)` | `decodeAs<Long>` (UNWRAPPED — must never error) |
| `sync_pull_watched_items_delta` | `(p_profile_id int, p_since_event_id int8, p_limit int)` | `decodeList<SupabaseWatchedItemEvent>` |
| `sync_delete_watched_items` | `(p_profile_id int, p_keys jsonb)` — **p_profile_id FIRST** | — |
| `sync_push_library` | `(p_items jsonb, p_profile_id int)` | — |
| `sync_pull_library` | `(p_profile_id int, p_limit int, p_offset int)` | `decodeList<SupabaseLibraryItem>` |

Push payload keys (verbatim from client):
- watched_items `p_items[]`: `{content_id, content_type, title, season (int|null), episode (int|null), watched_at}` (movies send `season`/`episode` as **explicit JSON null**).
- watched_items `p_keys[]` (delete): `{content_id, season?, episode?}` (season/episode **omitted** when null).
- library `p_items[]`: `{content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating?(double; omitted when null), genres[](string array), addon_base_url, added_at}`.

Decode model required-non-null fields (kotlinx throws otherwise):
- `SupabaseWatchedItem`: `content_id, content_type, watched_at`; `title`=`''`; `season/episode/id/user_id` optional; `profile_id`=1.
- `SupabaseWatchedItemEvent`: `event_id, operation, content_id, content_type, watched_at`; `title`=`''`; `season/episode` nullable.
- `SupabaseLibraryItem`: `content_id, content_type`; `name`=`''`, `poster_shape`=`'POSTER'`, `genres`=`[]`, `added_at`=0, `profile_id`=1; `poster/background/description/release_info/addon_base_url/id/user_id` nullable; `imdb_rating` nullable `Float`.

---

## File Structure

All SQL files at **repo root** (matches Plan 1 + `member_*_setup.sql`). Test artifacts at root (`.gitignore` ignores `scripts/*`).

- `watched_items_setup.sql` / `watched_items_teardown.sql` — `watched_items` + `watched_items_events` tables, RLS, grants, function ACLs, 5 RPCs.
- `watched_items_test.sql` — watched-items assertions (built additively across Tasks 1–8).
- `library_setup.sql` / `library_teardown.sql` — `library` table, RLS, grants, function ACLs, 2 RPCs.
- `library_test.sql` — library assertions (built additively across Tasks 9–12).

> **Deploy vs test:** on the live project run ONLY the `*_setup.sql` files (after `get_sync_owner_setup.sql`). On the branch, `run_sync_tests.sh` applies `sync_test_helpers.sql`, then the setup files, then the test files (the latter rolled back).

> **Branch-DB testing model (same as Plan 1):** the branch already has the real Supabase `auth` surface + `get_sync_owner` (from Plan 1). `*_setup.sql` is applied for real (persists = deploy-to-branch); `*_test.sql` runs in one rolled-back transaction, so **every test DO block must set its own session at entry** (`test_login(...)`/`test_logout()`) — `set_config(..., true)` is transaction-local.

---

## Task 1: `watched_items` schema (NULL-aware unique key, RLS, grants)

**Files:**
- Create: `watched_items_setup.sql`
- Create: `watched_items_test.sql`

- [ ] **Step 1: Write the failing schema test**

Create `watched_items_test.sql`:

```sql
-- ============ watched_items: schema ============
do $$
begin
  assert to_regclass('public.watched_items') is not null, 'watched_items table missing';
  assert to_regclass('public.watched_items_events') is not null, 'watched_items_events table missing';

  -- R3/R6: NULL-aware composite unique key = the ON CONFLICT upsert target.
  assert exists (
    select 1 from pg_constraint
    where conrelid = 'public.watched_items'::regclass and contype = 'u'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute
           where attrelid = 'public.watched_items'::regclass and attnum = any(conkey))
          = array['content_id','episode','profile_id','season','user_id']
  ), 'watched_items needs UNIQUE(user_id, profile_id, content_id, season, episode)';
  -- The unique index must be NULLS NOT DISTINCT (else movies with NULL season/episode duplicate).
  assert exists (
    select 1 from pg_index i join pg_constraint c on c.conindid = i.indexrelid
    where c.conrelid = 'public.watched_items'::regclass and c.contype = 'u'
      and i.indnullsnotdistinct
  ), 'watched_items unique key must be NULLS NOT DISTINCT';

  -- RLS on both tables.
  assert (select relrowsecurity from pg_class where oid='public.watched_items'::regclass), 'RLS off on watched_items';
  assert (select relrowsecurity from pg_class where oid='public.watched_items_events'::regclass), 'RLS off on watched_items_events';

  -- Writes are RPC-only; SELECT scoped to authenticated (RLS), none for anon.
  assert not has_table_privilege('authenticated','public.watched_items','INSERT'), 'authenticated can INSERT watched_items';
  assert not has_table_privilege('authenticated','public.watched_items','UPDATE'), 'authenticated can UPDATE watched_items';
  assert not has_table_privilege('authenticated','public.watched_items','DELETE'), 'authenticated can DELETE watched_items';
  assert has_table_privilege('authenticated','public.watched_items','SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon','public.watched_items','SELECT'), 'anon must not SELECT watched_items';

  raise notice 'watched_items schema OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source .supabase_db.env && ./run_sync_tests.sh get_sync_owner_setup.sql watched_items_test.sql`
Expected: FAIL with `watched_items table missing`.

- [ ] **Step 3: Write the schema (start `watched_items_setup.sql`)**

Create `watched_items_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS — `ALL SYNC SQL TESTS PASSED`.

- [ ] **Step 5: Commit**

```bash
git add watched_items_setup.sql watched_items_test.sql
git commit -m "feat(sync): watched_items + events schema, NULL-aware unique key, RLS, grants"
```

---

## Task 2: `sync_push_watched_items` (watched_at guard + NULL-aware dedup + ACLs)

**Files:**
- Modify: `watched_items_setup.sql` (append functions + ACLs)
- Modify: `watched_items_test.sql` (append push tests)

- [ ] **Step 1: Append the failing push tests**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: push (R2 watched_at guard, R3/R6 NULL-aware dedup) ============
do $$
declare a uuid := '22222222-aaaa-2222-2222-222222222222';
        v_at bigint; v_events int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- R3/R6 CRITICAL: a movie pushed twice (season/episode = explicit NULL) must DEDUPE to ONE row.
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','M',
    'season', null, 'episode', null, 'watched_at', 1000)), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','M2',
    'season', null, 'episode', null, 'watched_at', 2000)), 1);
  assert (select count(*) from public.watched_items where user_id=a and content_id='mov1') = 1,
    'movie (NULL season/episode) must dedupe to ONE row via nulls-not-distinct key';
  select watched_at into v_at from public.watched_items where user_id=a and content_id='mov1';
  assert v_at = 2000, format('newer watched_at must win; got %s', v_at);

  -- Distinct episodes of the same series are DISTINCT rows.
  perform public.sync_push_watched_items(jsonb_build_array(
    jsonb_build_object('content_id','s1','content_type','series','title','S',
      'season',1,'episode',1,'watched_at',10),
    jsonb_build_object('content_id','s1','content_type','series','title','S',
      'season',1,'episode',2,'watched_at',10)), 1);
  assert (select count(*) from public.watched_items where user_id=a and content_id='s1') = 2,
    'distinct episodes must be distinct rows';

  -- R2: a STALE watched_at push must NOT regress and must NOT append an event.
  select count(*) into v_events from public.watched_items_events where user_id=a;
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','OLD',
    'season', null, 'episode', null, 'watched_at', 1)), 1);
  select watched_at into v_at from public.watched_items where user_id=a and content_id='mov1';
  assert v_at = 2000, format('stale push must not regress; got %s', v_at);
  assert (select count(*) from public.watched_items_events where user_id=a) = v_events,
    'stale push must not append an event';

  -- R2 boundary: an EQUAL watched_at push must NOT update or append (guard is ">", not ">=").
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','mov1','content_type','movie','title','EQUAL',
    'season', null, 'episode', null, 'watched_at', 2000)), 1);
  assert (select title from public.watched_items where user_id=a and content_id='mov1') = 'M2',
    'equal-watched_at push must not update the row';
  assert (select count(*) from public.watched_items_events where user_id=a) = v_events,
    'equal-watched_at push must not append an event';

  raise notice 'watched_items push OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: FAIL with `function public.sync_push_watched_items(jsonb, integer) does not exist`.

- [ ] **Step 3: Append push functions + ACLs to `watched_items_setup.sql`**

Append to `watched_items_setup.sql`:

```sql
-- 3. Push: explicit-owner inner fn + thin JWT-resolving wrapper. R2 watched_at-guarded upsert;
--    append an event ONLY when the row changed. R4 NULL-owner no-op.
create or replace function public.sync_push_watched_items_for(
  p_owner uuid, p_profile_id int, p_items jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; v_changed boolean;
begin
  if p_owner is null or p_items is null then return; end if;
  for e in select value from jsonb_array_elements(p_items) as t(value) loop
    insert into public.watched_items as wi(
      user_id, profile_id, content_id, content_type, title, season, episode, watched_at)
    values (
      p_owner, p_profile_id, e->>'content_id', e->>'content_type', coalesce(e->>'title',''),
      nullif(e->>'season','')::int, nullif(e->>'episode','')::int, (e->>'watched_at')::bigint)
    on conflict (user_id, profile_id, content_id, season, episode) do update
      set content_type=excluded.content_type, title=excluded.title,
          watched_at=excluded.watched_at, updated_at=now()
      where excluded.watched_at > wi.watched_at                  -- R2 guard ("strictly newer")
    returning true into v_changed;

    if v_changed then                                            -- NULL (no row) => not changed
      insert into public.watched_items_events(
        user_id, profile_id, operation, content_id, content_type, title, season, episode, watched_at)
      values (p_owner, p_profile_id, 'upsert', e->>'content_id', e->>'content_type',
        coalesce(e->>'title',''), nullif(e->>'season','')::int, nullif(e->>'episode','')::int,
        (e->>'watched_at')::bigint);
    end if;
  end loop;
end $$;

create or replace function public.sync_push_watched_items(
  p_items jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_watched_items_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

-- 3b. Function ACLs (inner _for revoked from members; wrapper is the only member-facing entry).
revoke all on function public.sync_push_watched_items_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_watched_items(jsonb, int) from public, anon;
grant  execute on function public.sync_push_watched_items(jsonb, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS. (If the movie-dedup assert FAILS with count=2, the `unique nulls not distinct` / `ON CONFLICT` inference is not collapsing NULL keys — STOP and escalate; the unique key is wrong.)

- [ ] **Step 5: Commit**

```bash
git add watched_items_setup.sql watched_items_test.sql
git commit -m "feat(sync): sync_push_watched_items (watched_at guard, NULL-aware dedup, ACLs)"
```

---

## Task 3: `sync_pull_watched_items` (1-based paging, exact shape, stable order)

**Files:**
- Modify: `watched_items_setup.sql`
- Modify: `watched_items_test.sql`

- [ ] **Step 1: Append the failing pull tests (round-trip + R1 isolation + R7 shape + R8 paging)**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: pull (R1 isolation, R7 exact shape, R8 1-based paging) ============
do $$
declare a uuid := '33333333-aaaa-3333-3333-333333333333';
        b uuid := '44444444-aaaa-4444-4444-444444444444';
        v_count int; v_shape text; v_keys text;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wa','content_type','movie','title','A','season',null,'episode',null,'watched_at',1000)), 1);
  perform public.test_login(b);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wb','content_type','movie','title','B','season',null,'episode',null,'watched_at',1000)), 1);

  -- R1: member B pulls only B's rows, never A's. (page 1, page_size 900)
  select count(*), string_agg(content_id, ',' order by content_id) into v_count, v_keys
    from public.sync_pull_watched_items(1, 1, 900);
  assert v_count = 1, format('B should pull 1 row, got %s', v_count);
  assert v_keys = 'wb', format('B must not see A''s rows; got %s', v_keys);

  -- R7 wire-shape guard: pulled row JSON keys must EXACTLY match SupabaseWatchedItem's emitted set
  -- (no id, no updated_at). LIMIT the ROW first, THEN expand keys.
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_watched_items(1, 1, 900) limit 1 ) t ) s;
  assert v_shape = 'content_id,content_type,episode,profile_id,season,title,user_id,watched_at',
    format('pull row JSON keys must match SupabaseWatchedItem exactly; got: %s', v_shape);
end $$;

-- R8 1-based paging: page 2 continues where page 1 stopped, deterministic order, no dup/drop.
do $$
declare d uuid := '55555555-aaaa-5555-5555-555555555555';
        p1 text; p2 text; tot int;
begin
  insert into auth.users(id) values (d) on conflict do nothing;
  perform public.test_login(d);
  for i in 1..5 loop
    perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
      'content_id','p'||i,'content_type','series','title','P','season',1,'episode',i,'watched_at',i)), 1);
  end loop;
  -- page_size 2: page 1 = 2 rows, page 2 = next 2 rows, disjoint, ordered.
  select string_agg(content_id||':'||episode, ',' order by watched_at, content_id) into p1
    from public.sync_pull_watched_items(1, 1, 2);
  select string_agg(content_id||':'||episode, ',' order by watched_at, content_id) into p2
    from public.sync_pull_watched_items(1, 2, 2);
  assert (select count(*) from public.sync_pull_watched_items(1, 1, 2)) = 2, 'page 1 must have 2 rows';
  assert (select count(*) from public.sync_pull_watched_items(1, 2, 2)) = 2, 'page 2 must have 2 rows';
  assert p1 <> p2, 'pages must be disjoint';
  assert not exists (
    select content_id from public.sync_pull_watched_items(1, 1, 2)
    intersect
    select content_id from public.sync_pull_watched_items(1, 2, 2)
  ), 'paging must not overlap (R8 stable order)';
  raise notice 'watched_items pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: FAIL with `function public.sync_pull_watched_items(integer, integer, integer) does not exist`.

- [ ] **Step 3: Append the pull function (+ grant)**

Append to `watched_items_setup.sql`:

```sql
-- 4. Pull snapshot. SECURITY DEFINER + explicit owner predicate (R1). Exact SupabaseWatchedItem
--    shape (R7): user_id::text + the 7 other emitted model keys (id omitted — optional). 1-based
--    paging: offset = (p_page-1)*p_page_size. R8 total order with a unique tiebreaker.
create or replace function public.sync_pull_watched_items(
  p_profile_id int, p_page int, p_page_size int
) returns table(
  user_id text, content_id text, content_type text, title text,
  season int, episode int, watched_at bigint, profile_id int
) language sql security definer set search_path = '' as $$
  select wi.user_id::text, wi.content_id, wi.content_type, wi.title,
         wi.season, wi.episode, wi.watched_at, wi.profile_id
  from public.watched_items wi
  where wi.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and wi.profile_id = p_profile_id
  order by wi.watched_at asc, wi.content_id asc,
           wi.season asc nulls first, wi.episode asc nulls first    -- R8 total order: NULL-safe,
                                                                    -- avoids coalesce(-1) aliasing a real -1
  limit  greatest(p_page_size, 0)
  offset greatest((p_page - 1) * p_page_size, 0)                    -- 1-based page
$$;
revoke all     on function public.sync_pull_watched_items(int, int, int) from public, anon;
grant  execute on function public.sync_pull_watched_items(int, int, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watched_items_setup.sql watched_items_test.sql
git commit -m "feat(sync): sync_pull_watched_items (1-based paging, exact shape, stable order)"
```

---

## Task 4: `sync_get_watched_items_delta_cursor` (coalesce to 0, must never error)

**Files:**
- Modify: `watched_items_setup.sql`
- Modify: `watched_items_test.sql`

- [ ] **Step 1: Append the failing cursor test**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: delta cursor (R5 coalesce, never NULL — client is UNWRAPPED) ============
do $$
declare z uuid := '66666666-aaaa-6666-6666-666666666666';
        c1 bigint; c2 bigint; v_max bigint;
begin
  insert into auth.users(id) values (z) on conflict do nothing;
  perform public.test_login(z);

  -- Brand-new member, zero events: MUST return 0 (the client decodes a non-null Long and does NOT
  -- wrap this call — a NULL breaks watched-history restore outright, spec §5.2/R5).
  select public.sync_get_watched_items_delta_cursor(1) into c1;
  assert c1 = 0, format('empty cursor must be 0, got %s', c1);

  -- After a push, the cursor equals this owner's own max event_id.
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','cc','content_type','movie','title','C','season',null,'episode',null,'watched_at',5)), 1);
  select public.sync_get_watched_items_delta_cursor(1) into c2;
  select max(event_id) into v_max from public.watched_items_events where user_id=z;
  assert c2 = v_max, format('cursor must equal owner max event_id %s, got %s', v_max, c2);

  raise notice 'watched_items delta cursor OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: FAIL with `function public.sync_get_watched_items_delta_cursor(integer) does not exist`.

- [ ] **Step 3: Append the cursor function (+ grant)**

Append to `watched_items_setup.sql`:

```sql
-- 5. Delta cursor: latest event_id for THIS owner, coalesced to 0 (R5). The client does NOT wrap
--    this call (WatchedItemsSyncService.kt) — a bare max() NULL would crash watched-history restore.
create or replace function public.sync_get_watched_items_delta_cursor(p_profile_id int)
  returns bigint language sql security definer set search_path = '' as $$
  select coalesce(max(event_id), 0)
  from public.watched_items_events
  where user_id = nullif(public.get_sync_owner(),'')::uuid          -- R1
    and profile_id = p_profile_id
$$;
revoke all     on function public.sync_get_watched_items_delta_cursor(int) from public, anon;
grant  execute on function public.sync_get_watched_items_delta_cursor(int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watched_items_setup.sql watched_items_test.sql
git commit -m "feat(sync): sync_get_watched_items_delta_cursor (coalesce to 0)"
```

---

## Task 5: `sync_pull_watched_items_delta` (owner-scoped, ascending, limited)

**Files:**
- Modify: `watched_items_setup.sql`
- Modify: `watched_items_test.sql`

- [ ] **Step 1: Append the failing delta-pull test**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: delta pull (R1 owner, R7 shape, R8 asc+limit) ============
do $$
declare d uuid := '77777777-aaaa-7777-7777-777777777777';
        e uuid := '88888888-aaaa-8888-8888-888888888888';
        n int; first_id bigint; last_id bigint; v_shape text;
begin
  insert into auth.users(id) values (d),(e) on conflict do nothing;

  perform public.test_login(e);   -- noise from another member must never appear in d's delta
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','noise','content_type','movie','title','N','season',null,'episode',null,'watched_at',1)), 1);

  perform public.test_login(d);
  for i in 1..3 loop
    perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
      'content_id','k'||i,'content_type','series','title','K','season',1,'episode',i,'watched_at',i)), 1);
  end loop;

  -- From cursor 0, limit 2: exactly 2 of d's events, ascending, none of e's.
  select count(*), min(event_id), max(event_id) into n, first_id, last_id
    from public.sync_pull_watched_items_delta(1, 0, 2);
  assert n = 2, format('expected 2 delta rows, got %s', n);
  assert first_id < last_id, 'delta rows must be ascending by event_id';
  assert not exists (
    select 1 from public.sync_pull_watched_items_delta(1, 0, 100) where content_id='noise'
  ), 'd must never see member e''s events';

  -- R7 delta wire-shape: exact SupabaseWatchedItemEvent key set.
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_watched_items_delta(1, 0, 1) limit 1 ) t ) s;
  assert v_shape = 'content_id,content_type,episode,event_id,operation,season,title,watched_at',
    format('delta row JSON keys must match SupabaseWatchedItemEvent exactly; got: %s', v_shape);

  raise notice 'watched_items delta pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: FAIL with `function public.sync_pull_watched_items_delta(...) does not exist`.

- [ ] **Step 3: Append the delta-pull function (+ grant)**

Append to `watched_items_setup.sql`:

```sql
-- 6. Delta pull: owner-scoped (R1), event_id > cursor, ASC, limited (R8). Exact
--    SupabaseWatchedItemEvent shape (R7); delete event rows carry zeroed watched_at.
create or replace function public.sync_pull_watched_items_delta(
  p_profile_id int, p_since_event_id bigint, p_limit int
) returns table(
  event_id bigint, operation text, content_id text, content_type text,
  title text, season int, episode int, watched_at bigint
) language sql security definer set search_path = '' as $$
  select ev.event_id, ev.operation, ev.content_id, ev.content_type,
         ev.title, ev.season, ev.episode, ev.watched_at
  from public.watched_items_events ev
  where ev.user_id = nullif(public.get_sync_owner(),'')::uuid       -- R1
    and ev.profile_id = p_profile_id
    and ev.event_id > p_since_event_id
  order by ev.event_id asc                                          -- R8
  limit p_limit
$$;
revoke all     on function public.sync_pull_watched_items_delta(int, bigint, int) from public, anon;
grant  execute on function public.sync_pull_watched_items_delta(int, bigint, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watched_items_setup.sql watched_items_test.sql
git commit -m "feat(sync): sync_pull_watched_items_delta (owner-scoped, ascending)"
```

---

## Task 6: `sync_delete_watched_items` (p_profile_id FIRST, object keys, IS NOT DISTINCT FROM)

**Files:**
- Modify: `watched_items_setup.sql`
- Modify: `watched_items_test.sql`

- [ ] **Step 1: Append the failing delete test**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: delete (R6 object keys + IS NOT DISTINCT FROM; delete events) ============
do $$
declare g uuid := '99999999-aaaa-9999-9999-999999999999';
        n int; del_ct text; del_at bigint; del_events int;
begin
  insert into auth.users(id) values (g) on conflict do nothing;
  perform public.test_login(g);

  -- one movie (NULL season/episode) + two episodes of a series.
  perform public.sync_push_watched_items(jsonb_build_array(
    jsonb_build_object('content_id','dm','content_type','movie','title','DM','season',null,'episode',null,'watched_at',1),
    jsonb_build_object('content_id','ds','content_type','series','title','DS','season',1,'episode',1,'watched_at',1),
    jsonb_build_object('content_id','ds','content_type','series','title','DS','season',1,'episode',2,'watched_at',1)), 1);

  -- R6: delete the MOVIE with a content_id-ONLY key (season/episode omitted => NULL =>
  --     IS NOT DISTINCT FROM matches the NULL-season movie row). p_profile_id is FIRST.
  perform public.sync_delete_watched_items(1, jsonb_build_array(jsonb_build_object('content_id','dm')));
  assert not exists (select 1 from public.watched_items where user_id=g and content_id='dm'),
    'movie must be deleted via content_id-only key (IS NOT DISTINCT FROM NULL)';

  -- The delete event carries the deleted row's real content_type and zeroed watched_at (R7).
  select content_type, watched_at into del_ct, del_at
    from public.watched_items_events
    where user_id=g and operation='delete' and content_id='dm' order by event_id desc limit 1;
  assert del_ct = 'movie', format('delete event content_type must be the deleted row''s; got %s', del_ct);
  assert del_at = 0, format('delete event watched_at must be zeroed; got %s', del_at);

  -- R6: delete ONE episode by {content_id, season, episode}; the other episode survives.
  perform public.sync_delete_watched_items(1,
    jsonb_build_array(jsonb_build_object('content_id','ds','season',1,'episode',1)));
  select count(*) into n from public.watched_items where user_id=g and content_id='ds';
  assert n = 1, format('only s1e1 deleted; expected 1 episode left, got %s', n);
  assert exists (select 1 from public.watched_items where user_id=g and content_id='ds' and episode=2),
    's1e2 must survive';

  -- A pull must not resurrect the deleted rows.
  assert not exists (select 1 from public.sync_pull_watched_items(1,1,900) where content_id='dm'),
    'deleted movie must not reappear in a pull';

  select count(*) into del_events from public.watched_items_events where user_id=g and operation='delete';
  assert del_events = 2, format('expected 2 delete events (movie + s1e1), got %s', del_events);

  raise notice 'watched_items delete OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: FAIL with `function public.sync_delete_watched_items(integer, jsonb) does not exist`.

- [ ] **Step 3: Append the delete functions (+ ACLs)**

Append to `watched_items_setup.sql`:

```sql
-- 7. Delete: p_profile_id is FIRST (client arg order). p_keys = array of OBJECTS
--    {content_id, season?, episode?} (season/episode OMITTED => SQL NULL => matches movie rows).
--    Match with IS NOT DISTINCT FROM (R6). Append a delete event per removed row with the deleted
--    row's real content_type and zeroed watched_at (R7).
create or replace function public.sync_delete_watched_items_for(
  p_owner uuid, p_profile_id int, p_keys jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb; r record;
begin
  if p_owner is null or p_keys is null then return; end if;     -- R4
  for e in select value from jsonb_array_elements(p_keys) as t(value) loop
    for r in
      delete from public.watched_items wi
      where wi.user_id = p_owner and wi.profile_id = p_profile_id
        and wi.content_id = e->>'content_id'
        and wi.season  is not distinct from nullif(e->>'season','')::int
        and wi.episode is not distinct from nullif(e->>'episode','')::int
      returning wi.content_id, wi.content_type, wi.season, wi.episode
    loop
      insert into public.watched_items_events(
        user_id, profile_id, operation, content_id, content_type, title, season, episode, watched_at)
      values (p_owner, p_profile_id, 'delete', r.content_id, r.content_type, '', r.season, r.episode, 0);
    end loop;
  end loop;
end $$;

create or replace function public.sync_delete_watched_items(
  p_profile_id int, p_keys jsonb
) returns void language sql security definer set search_path = '' as $$
  select public.sync_delete_watched_items_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_keys)
$$;

-- 7b. Function ACLs: lock the inner _for fn, expose only the wrapper to members.
revoke all on function public.sync_delete_watched_items_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_delete_watched_items(int, jsonb) from public, anon;
grant  execute on function public.sync_delete_watched_items(int, jsonb) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add watched_items_setup.sql watched_items_test.sql
git commit -m "feat(sync): sync_delete_watched_items (object keys, IS NOT DISTINCT FROM, delete events + ACLs)"
```

---

## Task 7: `watched_items` function-ACL + NULL-owner + RLS read-own assertions

**Files:**
- Modify: `watched_items_test.sql`

(No setup change — proves the ACLs/guards already added hold.)

- [ ] **Step 1: Append the ACL + NULL-owner + RLS test**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: function ACLs ============
do $$
begin
  assert not has_function_privilege('authenticated',
    'public.sync_push_watched_items_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert not has_function_privilege('authenticated',
    'public.sync_delete_watched_items_for(uuid,int,jsonb)', 'EXECUTE'),
    'delete _for must NOT be executable by authenticated';

  assert has_function_privilege('authenticated', 'public.sync_push_watched_items(jsonb,int)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watched_items(int,int,int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_get_watched_items_delta_cursor(int)', 'EXECUTE'),
    'cursor wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_watched_items_delta(int,bigint,int)', 'EXECUTE'),
    'delta wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_delete_watched_items(int,jsonb)', 'EXECUTE'),
    'delete wrapper must be executable by authenticated';

  raise notice 'watched_items ACLs OK';
end $$;

-- ============ watched_items: RLS read-own (D — defense-in-depth) ============
do $$
declare a uuid := 'aaaaaaaa-bbbb-aaaa-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','rls_a','content_type','movie','title','A','season',null,'episode',null,'watched_at',1)), 1);
  perform public.test_login(b);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','rls_b','content_type','movie','title','B','season',null,'episode',null,'watched_at',1)), 1);
  perform public.test_login(a);
end $$;

set local role authenticated;
do $$
declare foreign_n int; own_n int;
begin
  select count(*) into foreign_n from public.watched_items where content_id='rls_b';
  assert foreign_n = 0, 'RLS must hide member B''s rows from A on a direct table read';
  select count(*) into own_n from public.watched_items where content_id='rls_a';
  assert own_n >= 1, 'A must see its OWN row under RLS';
  raise notice 'watched_items RLS read-own OK';
end $$;
reset role;

-- ============ watched_items: NULL-owner safety (R4) ============
do $$
declare before_rows int; after_rows int; cur bigint;
begin
  perform public.test_logout();   -- no JWT => get_sync_owner() is NULL
  select count(*) into before_rows from public.watched_items;

  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','n','content_type','movie','title','N','season',null,'episode',null,'watched_at',1)), 1);
  perform public.sync_delete_watched_items(1, jsonb_build_array(jsonb_build_object('content_id','n')));

  select count(*) into after_rows from public.watched_items;
  assert after_rows = before_rows, 'anon push/delete must not change row count';
  assert not exists (select 1 from public.watched_items where user_id is null), 'no NULL-user_id rows';

  assert (select count(*) from public.sync_pull_watched_items(1, 1, 900)) = 0, 'anon pull must be empty';
  select public.sync_get_watched_items_delta_cursor(1) into cur;
  assert cur = 0, format('anon cursor must be 0, got %s', cur);

  raise notice 'watched_items NULL-owner OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS. (If an ACL assert FAILS, a revoke/grant is missing from Task 2/3/4/5/6 — fix there. If a NULL-owner assert FAILS, a wrapper is missing its `nullif(get_sync_owner(),'')::uuid` cast or an inner fn its `if p_owner is null` guard.)

- [ ] **Step 3: Commit**

```bash
git add watched_items_test.sql
git commit -m "test(sync): assert watched_items function ACLs + NULL-owner safety + RLS read-own"
```

---

## Task 8: `watched_items` teardown + data-preserving idempotency

**Files:**
- Create: `watched_items_teardown.sql`
- Modify: `watched_items_test.sql`

- [ ] **Step 1: Append the idempotency test (re-apply preserves data)**

Append to `watched_items_test.sql`:

```sql
-- ============ watched_items: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-aaaa-ffff-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','idemp','content_type','movie','title','SENT','season',null,'episode',null,'watched_at',7)), 1);
end $$;

-- re-apply the whole setup mid-test (create-if-not-exists / or-replace).
-- NOTE: keep the comment off the \i line — psql parses a trailing inline comment as extra
-- \i arguments and emits noisy "extra argument ignored" warnings.
\i watched_items_setup.sql

do $$
declare s uuid := 'ffffffff-aaaa-ffff-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert to_regclass('public.watched_items') is not null, 're-apply dropped the table';
  assert (select count(*) from public.watched_items where user_id=s and content_id='idemp') = 1,
    're-applying setup must PRESERVE existing rows (no drop-then-create)';
  assert (select count(*) from public.watched_items_events where user_id=s and content_id='idemp') = 1,
    're-applying setup must preserve existing events';
  raise notice 'watched_items idempotency OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_test.sql`
Expected: PASS.

- [ ] **Step 3: Write the teardown**

Create `watched_items_teardown.sql`:

```sql
-- Rollback of watched_items_setup.sql. Removes ONLY watched_items objects. Does NOT touch
-- get_sync_owner (shared), auth.users, watch_progress, library, member_*, or telemetry.
-- DROPS the stored watched-history data. Idempotent.

-- 1. Functions (wrappers + inner fns).
drop function if exists public.sync_push_watched_items(jsonb, int);
drop function if exists public.sync_push_watched_items_for(uuid, int, jsonb);
drop function if exists public.sync_pull_watched_items(int, int, int);
drop function if exists public.sync_get_watched_items_delta_cursor(int);
drop function if exists public.sync_pull_watched_items_delta(int, bigint, int);
drop function if exists public.sync_delete_watched_items(int, jsonb);
drop function if exists public.sync_delete_watched_items_for(uuid, int, jsonb);

-- 2. Tables (RLS policies + indexes drop with the table).
drop table if exists public.watched_items_events;
drop table if exists public.watched_items;

-- Sanity: both should report NULL (gone).
select 'watched_items' as obj, to_regclass('public.watched_items') as still_exists
union all select 'watched_items_events', to_regclass('public.watched_items_events');
```

- [ ] **Step 4: Verify teardown drops cleanly, then restore the branch**

```bash
./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql watched_items_teardown.sql
# teardown leaves the branch without watched_items — re-apply setup so the branch is deployed again:
./run_sync_tests.sh get_sync_owner_setup.sql watched_items_setup.sql
```
Expected: teardown applies cleanly (final sanity `select` shows both objects NULL); re-apply exits 0. Confirm restored:
```bash
psql "$SYNC_TEST_DB_URL" -At -c "select 'wi='||coalesce(to_regclass('public.watched_items')::text,'MISSING')"
```
Expected: `wi=watched_items`.

- [ ] **Step 5: Commit**

```bash
git add watched_items_teardown.sql watched_items_test.sql
git commit -m "feat(sync): watched_items teardown + data-preserving idempotency test"
```

---

## Task 9: `library` schema (snapshot table, RLS, grants)

**Files:**
- Create: `library_setup.sql`
- Create: `library_test.sql`

- [ ] **Step 1: Write the failing schema test**

Create `library_test.sql`:

```sql
-- ============ library: schema ============
do $$
begin
  assert to_regclass('public.library') is not null, 'library table missing';

  -- R3: PK = the ON CONFLICT upsert target (user_id, profile_id, content_id).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.library'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.library'::regclass and attnum=any(conkey))
          = array['content_id','profile_id','user_id']
  ), 'library PK must be (user_id, profile_id, content_id)';

  -- genres is text[]; imdb_rating is nullable real.
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.library'::regclass and attname='genres') = 'text[]',
    'genres must be text[]';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.library'::regclass and attname='imdb_rating') = 'real',
    'imdb_rating must be real (float4)';

  assert (select relrowsecurity from pg_class where oid='public.library'::regclass), 'RLS off on library';
  assert not has_table_privilege('authenticated','public.library','INSERT'), 'authenticated can INSERT library';
  assert not has_table_privilege('authenticated','public.library','UPDATE'), 'authenticated can UPDATE library';
  assert not has_table_privilege('authenticated','public.library','DELETE'), 'authenticated can DELETE library';
  assert has_table_privilege('authenticated','public.library','SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon','public.library','SELECT'), 'anon must not SELECT library';

  raise notice 'library schema OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_test.sql`
Expected: FAIL with `library table missing`.

- [ ] **Step 3: Write the schema (start `library_setup.sql`)**

Create `library_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add library_setup.sql library_test.sql
git commit -m "feat(sync): library snapshot schema (PK, RLS, grants)"
```

---

## Task 10: `sync_push_library` (upsert, genres text[], imdb_rating real, ACLs)

**Files:**
- Modify: `library_setup.sql`
- Modify: `library_test.sql`

- [ ] **Step 1: Append the failing push tests**

Append to `library_test.sql`:

```sql
-- ============ library: push (upsert; genres text[]; imdb_rating real; omitted-null handling) ============
do $$
declare a uuid := '11111111-bbbb-1111-1111-111111111111';
        v_name text; v_rating real; v_genres text[]; v_rating2 real; n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- Full item with imdb_rating + genres array.
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lib1','content_type','movie','name','Film','poster','p','poster_shape','POSTER',
    'background','bg','description','d','release_info','2024',
    'imdb_rating', 7.5, 'genres', jsonb_build_array('Action','Drama'),
    'addon_base_url','http://a','added_at', 100)), 1);
  select name, imdb_rating, genres into v_name, v_rating, v_genres
    from public.library where user_id=a and content_id='lib1';
  assert v_name = 'Film', format('name mismatch: %s', v_name);
  assert v_rating = 7.5::real, format('imdb_rating mismatch: %s', v_rating);
  assert v_genres = array['Action','Drama'], format('genres mismatch: %s', v_genres::text);

  -- Item with imdb_rating OMITTED (client drops the key when null) => stored NULL.
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lib2','content_type','series','name','Show','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 50)), 1);
  select imdb_rating into v_rating2 from public.library where user_id=a and content_id='lib2';
  assert v_rating2 is null, format('omitted imdb_rating must store NULL; got %s', v_rating2);
  assert (select genres from public.library where user_id=a and content_id='lib2') = '{}'::text[],
    'empty genres array must store {}';

  -- R3: re-push same content_id updates the SAME row (snapshot last-write-wins).
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lib1','content_type','movie','name','Film v2','poster_shape','POSTER',
    'genres', jsonb_build_array('Action'), 'added_at', 200)), 1);
  select count(*), max(name) into n, v_name from public.library where user_id=a and content_id='lib1';
  assert n = 1, format('re-push must not duplicate; got %s rows', n);
  assert v_name = 'Film v2', format('re-push must overwrite name; got %s', v_name);

  raise notice 'library push OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_test.sql`
Expected: FAIL with `function public.sync_push_library(jsonb, integer) does not exist`.

- [ ] **Step 3: Append push functions + ACLs to `library_setup.sql`**

Append to `library_setup.sql`:

```sql
-- 3. Push: snapshot upsert (last-write-wins — library is a wholesale snapshot, no event log/guard).
--    genres stored as text[] (client pushes a JSON string array); imdb_rating omitted-when-null.
create or replace function public.sync_push_library_for(
  p_owner uuid, p_profile_id int, p_items jsonb
) returns void language plpgsql security definer set search_path = '' as $$
declare e jsonb;
begin
  if p_owner is null or p_items is null then return; end if;     -- R4
  for e in select value from jsonb_array_elements(p_items) as t(value) loop
    insert into public.library as lib(
      user_id, profile_id, content_id, content_type, name, poster, poster_shape,
      background, description, release_info, imdb_rating, genres, addon_base_url, added_at)
    values (
      p_owner, p_profile_id, e->>'content_id', e->>'content_type', coalesce(e->>'name',''),
      e->>'poster', coalesce(e->>'poster_shape','POSTER'), e->>'background', e->>'description',
      e->>'release_info', nullif(e->>'imdb_rating','')::real,
      -- genres: guard the type so an explicit JSON `"genres": null` can't raise
      -- "cannot extract elements from a scalar" (absent key already yields '{}'). Defense-in-depth —
      -- the verified client always sends a JSON array, but this makes the push total over any input.
      case when jsonb_typeof(e->'genres') = 'array'
           then coalesce((select array_agg(g) from jsonb_array_elements_text(e->'genres') as t(g)), '{}')
           else '{}' end,
      e->>'addon_base_url', coalesce((e->>'added_at')::bigint, 0))
    on conflict (user_id, profile_id, content_id) do update
      set content_type=excluded.content_type, name=excluded.name, poster=excluded.poster,
          poster_shape=excluded.poster_shape, background=excluded.background,
          description=excluded.description, release_info=excluded.release_info,
          imdb_rating=excluded.imdb_rating, genres=excluded.genres,
          addon_base_url=excluded.addon_base_url, added_at=excluded.added_at, updated_at=now();
  end loop;
end $$;

create or replace function public.sync_push_library(
  p_items jsonb, p_profile_id int
) returns void language sql security definer set search_path = '' as $$
  select public.sync_push_library_for(
    nullif(public.get_sync_owner(),'')::uuid, p_profile_id, p_items)
$$;

-- 3b. Function ACLs.
revoke all on function public.sync_push_library_for(uuid, int, jsonb) from public, anon, authenticated;
revoke all     on function public.sync_push_library(jsonb, int) from public, anon;
grant  execute on function public.sync_push_library(jsonb, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add library_setup.sql library_test.sql
git commit -m "feat(sync): sync_push_library (snapshot upsert, genres text[], imdb_rating real, ACLs)"
```

---

## Task 11: `sync_pull_library` (exact 14-key shape, genres array round-trip, stable order)

**Files:**
- Modify: `library_setup.sql`
- Modify: `library_test.sql`

- [ ] **Step 1: Append the failing pull tests (round-trip + R1 + R7 shape + genres array + R8 offset)**

Append to `library_test.sql`:

```sql
-- ============ library: pull (R1 isolation, R7 exact 14-key shape, genres JSON array, R8 offset) ============
do $$
declare a uuid := '22222222-bbbb-2222-2222-222222222222';
        b uuid := '33333333-bbbb-3333-3333-333333333333';
        v_count int; v_keys text; v_shape text; v_genres_json text; v_rating_json jsonb;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','la','content_type','movie','name','LA','poster_shape','POSTER',
    'imdb_rating', 8.1, 'genres', jsonb_build_array('Sci-Fi','Thriller'), 'added_at', 10)), 1);
  perform public.test_login(b);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','lb','content_type','movie','name','LB','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 10)), 1);

  -- R1: member B pulls only B's rows.
  select count(*), string_agg(content_id, ',') into v_count, v_keys
    from public.sync_pull_library(1, 500, 0);
  assert v_count = 1, format('B should pull 1 row, got %s', v_count);
  assert v_keys = 'lb', format('B must not see A''s rows; got %s', v_keys);

  -- R7 wire-shape guard: exact 14-key SupabaseLibraryItem emitted set (id omitted).
  perform public.test_login(a);
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_library(1, 500, 0) limit 1 ) t ) s;
  assert v_shape = 'added_at,addon_base_url,background,content_id,content_type,description,genres,imdb_rating,name,poster,poster_shape,profile_id,release_info,user_id',
    format('pull row JSON keys must match SupabaseLibraryItem exactly; got: %s', v_shape);

  -- genres must serialize as a JSON ARRAY of strings (decodes to List<String>), not a Postgres
  -- array literal string; imdb_rating must serialize as a JSON number (decodes to Float?).
  select to_jsonb(t)->>'genres', to_jsonb(t)->'imdb_rating' into v_genres_json, v_rating_json
  from ( select * from public.sync_pull_library(1, 500, 0) where content_id='la' limit 1 ) t;
  assert v_genres_json = '["Sci-Fi", "Thriller"]',
    format('genres must be a JSON array; got: %s', v_genres_json);
  assert v_rating_json = '8.1'::jsonb, format('imdb_rating must be a JSON number; got: %s', v_rating_json);

  raise notice 'library pull OK';
end $$;

-- R8 offset paging: two pages of size 1 are disjoint, deterministic.
do $$
declare d uuid := '44444444-bbbb-4444-4444-444444444444';
begin
  insert into auth.users(id) values (d) on conflict do nothing;
  perform public.test_login(d);
  for i in 1..3 loop
    perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
      'content_id','pg'||i,'content_type','movie','name','PG','poster_shape','POSTER',
      'genres', jsonb_build_array(), 'added_at', i)), 1);
  end loop;
  assert (select count(*) from public.sync_pull_library(1, 2, 0)) = 2, 'limit 2 offset 0 => 2 rows';
  assert (select count(*) from public.sync_pull_library(1, 2, 2)) = 1, 'limit 2 offset 2 => 1 row';
  assert not exists (
    select content_id from public.sync_pull_library(1, 2, 0)
    intersect
    select content_id from public.sync_pull_library(1, 2, 2)
  ), 'offset paging must not overlap (R8 stable order)';
  raise notice 'library paging OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_test.sql`
Expected: FAIL with `function public.sync_pull_library(integer, integer, integer) does not exist`.

- [ ] **Step 3: Append the pull function (+ grant)**

Append to `library_setup.sql`:

```sql
-- 4. Pull snapshot. Owner-scoped (R1). Exact SupabaseLibraryItem shape (R7): 14 keys (id omitted).
--    genres text[] serializes to a JSON array; imdb_rating real -> JSON number/null. R8 stable order;
--    offset paging (client page size 500).
create or replace function public.sync_pull_library(
  p_profile_id int, p_limit int, p_offset int
) returns table(
  user_id text, content_id text, content_type text, name text, poster text,
  poster_shape text, background text, description text, release_info text,
  imdb_rating real, genres text[], addon_base_url text, added_at bigint, profile_id int
) language sql security definer set search_path = '' as $$
  select lib.user_id::text, lib.content_id, lib.content_type, lib.name, lib.poster,
         lib.poster_shape, lib.background, lib.description, lib.release_info,
         lib.imdb_rating, lib.genres, lib.addon_base_url, lib.added_at, lib.profile_id
  from public.library lib
  where lib.user_id = nullif(public.get_sync_owner(),'')::uuid      -- R1
    and lib.profile_id = p_profile_id
  order by lib.added_at desc, lib.content_id asc                    -- R8 total order (content_id unique per owner)
  limit  greatest(p_limit, 0)
  offset greatest(p_offset, 0)
$$;
revoke all     on function public.sync_pull_library(int, int, int) from public, anon;
grant  execute on function public.sync_pull_library(int, int, int) to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_test.sql`
Expected: PASS. (If the `genres` JSON-array assert FAILS — e.g. it serialized as `{Sci-Fi,Thriller}` — `text[]` did not round-trip; STOP and escalate to switch `genres` to `jsonb`.)

- [ ] **Step 5: Commit**

```bash
git add library_setup.sql library_test.sql
git commit -m "feat(sync): sync_pull_library (exact shape, genres JSON array, stable offset paging)"
```

---

## Task 12: `library` ACL + NULL-owner + teardown + idempotency

**Files:**
- Create: `library_teardown.sql`
- Modify: `library_test.sql`

- [ ] **Step 1: Append the ACL + NULL-owner + RLS + idempotency tests**

Append to `library_test.sql`:

```sql
-- ============ library: function ACLs + NULL-owner + RLS read-own ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_library_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_library(jsonb,int)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_library(int,int,int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  raise notice 'library ACLs OK';
end $$;

-- RLS read-own.
do $$
declare a uuid := 'aaaaaaaa-cccc-aaaa-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-cccc-bbbb-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','rls_la','content_type','movie','name','A','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 1)), 1);
  perform public.test_login(b);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','rls_lb','content_type','movie','name','B','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 1)), 1);
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.library where content_id='rls_lb') = 0,
    'RLS must hide member B''s library from A';
  assert (select count(*) from public.library where content_id='rls_la') >= 1, 'A must see its own library';
  raise notice 'library RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4).
do $$
declare before_rows int; after_rows int;
begin
  perform public.test_logout();
  select count(*) into before_rows from public.library;
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','n','content_type','movie','name','N','poster_shape','POSTER',
    'genres', jsonb_build_array(), 'added_at', 1)), 1);
  select count(*) into after_rows from public.library;
  assert after_rows = before_rows, 'anon push must not change row count';
  assert not exists (select 1 from public.library where user_id is null), 'no NULL-user_id rows';
  assert (select count(*) from public.sync_pull_library(1, 500, 0)) = 0, 'anon pull must be empty';
  raise notice 'library NULL-owner OK';
end $$;

-- ============ library: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-cccc-ffff-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','idemp','content_type','movie','name','SENT','poster_shape','POSTER',
    'genres', jsonb_build_array('G'), 'added_at', 9)), 1);
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line — see Task 8 note).
\i library_setup.sql

do $$
declare s uuid := 'ffffffff-cccc-ffff-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.library where user_id=s and content_id='idemp') = 1,
    're-applying setup must PRESERVE existing rows';
  raise notice 'library idempotency OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_test.sql`
Expected: PASS.

- [ ] **Step 3: Write the teardown**

Create `library_teardown.sql`:

```sql
-- Rollback of library_setup.sql. Removes ONLY library objects. Does NOT touch get_sync_owner
-- (shared), auth.users, watch_progress, watched_items, member_*, or telemetry. DROPS the stored
-- library data. Idempotent.

drop function if exists public.sync_push_library(jsonb, int);
drop function if exists public.sync_push_library_for(uuid, int, jsonb);
drop function if exists public.sync_pull_library(int, int, int);

drop table if exists public.library;

select 'library' as obj, to_regclass('public.library') as still_exists;
```

- [ ] **Step 4: Verify teardown drops cleanly, then restore the branch**

```bash
./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql library_teardown.sql
./run_sync_tests.sh get_sync_owner_setup.sql library_setup.sql
psql "$SYNC_TEST_DB_URL" -At -c "select 'library='||coalesce(to_regclass('public.library')::text,'MISSING')"
```
Expected: teardown clean (sanity `select` shows NULL); re-apply exits 0; final check prints `library=library`.

- [ ] **Step 5: Commit**

```bash
git add library_teardown.sql library_test.sql
git commit -m "feat(sync): library ACL + NULL-owner + RLS tests, teardown + idempotency"
```

---

## Task 13: Full-suite green (all subsystems) + contract cross-check + go-live gate

**Files:** none (verification only).

- [ ] **Step 1: Run the complete cross-subsystem suite from scratch**

Run (Plan 1 + Plan 2 together — proves the shared `get_sync_owner` foundation serves all three subsystems with no regression):
```bash
source .supabase_db.env
./run_sync_tests.sh \
  get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  watch_progress_test.sql watched_items_test.sql library_test.sql
```
Expected: PASS — `ALL SYNC SQL TESTS PASSED`, covering Plan 1's 11 watch_progress blocks plus all watched_items + library blocks (schema/keys/RLS/grants, push guard+NULL-aware-dedup, pull isolation+shape+paging, cursor coalesce, delta asc+limit, delete object-keys+IS-NOT-DISTINCT, ACLs, NULL-owner, idempotency; library push+pull+genres-array+ACL+NULL-owner+idempotency).

- [ ] **Step 2: Confirm the RPC contract matches the client call sites**

Manually diff against the client (no code change — a contract check). Every function name + **argument-name set** must match exactly (PostgREST binds by name):
- `sync_push_watched_items(p_items jsonb, p_profile_id int)` ↔ `WatchedItemsSyncService.kt:140-158`
- `sync_pull_watched_items(p_profile_id int, p_page int, p_page_size int)` ↔ `:185-191`
- `sync_get_watched_items_delta_cursor(p_profile_id int)` ↔ `:93-97`
- `sync_pull_watched_items_delta(p_profile_id int, p_since_event_id int8, p_limit int)` ↔ `:105-111`
- `sync_delete_watched_items(p_profile_id int, p_keys jsonb)` ↔ `:332-343` / `:362-375` (**p_profile_id first**)
- `sync_push_library(p_items jsonb, p_profile_id int)` ↔ `LibrarySyncService.kt:51-75`
- `sync_pull_library(p_profile_id int, p_limit int, p_offset int)` ↔ `:93-99`

Confirm decode targets: watched-items pull→`SupabaseWatchedItem`, delta→`SupabaseWatchedItemEvent`, cursor→`Long`; library pull→`SupabaseLibraryItem`. The Task 3/5/11 wire-shape asserts already pin the exact emitted key sets.

- [ ] **Step 3: Record the go-live gate**

This plan delivers + validates `watched_items` and `library` **on the branch**. Per spec §12, do **NOT** apply `watched_items_setup.sql` / `library_setup.sql` (or `get_sync_owner_setup.sql`) to the **live** project fleet-wide until Plan 3 ships the §9 detection probe, §8 canary path, and §10 runbook (the only rollback today is data-destructive teardown). Branch + a single canary build is allowed now. No commit — this is a checklist gate.

---

## Self-Review (author checklist — completed at write time)

**1. Spec coverage (Plan 2 scope = §5.2 + §5.3, applicable R1–R9):**
- §5.2 watched_items 2 tables + 5 RPCs → Tasks 1–8. ✓ · §5.3 library 1 table + 2 RPCs → Tasks 9–12. ✓
- R1 owner predicate on every read + scoped write/delete → Tasks 3/4/5/6/11 (+ T-ISO in 3, 5, 11). ✓
- R2 watched_at guard incl. equal-ts boundary → Task 2. ✓ · R3 PK/unique upsert target → Tasks 1 & 9. ✓
- **R6 NULL-aware key (movies dedupe via `nulls not distinct`) + delete `IS NOT DISTINCT FROM` (object keys) + reversed delete arg order** → Tasks 1 (key), 2 (dedup test), 6 (delete). ✓
- R4 NULL-owner → Tasks 7 & 12. ✓ · R5 coalesce cursor + exact-max → Task 4. ✓
- R7 exact wire shape (LIMIT-before-expand) for watched-items pull/delta + library pull (incl. genres-as-JSON-array + imdb_rating-as-number) + zeroed-watched_at delete events → Tasks 3, 5, 6, 11. ✓
- R8 1-based paging (watched-items) + offset paging (library) total-order + delta asc → Tasks 3, 5, 11. ✓ · R9 idempotent setup / scoped teardown → Tasks 8 & 12. ✓
- Function ACLs (inner `_for` revoked, wrappers granted, asserted) → Tasks 2/6/10 + 7/12. ✓
- R10 retention (`prune_sync_events`) — **deferred to Plan 3** (shared across all `*_events` tables). Noted.

**2. Placeholder scan:** No TBD/TODO; every code step contains complete SQL. ✓

**3. Type consistency:** inner fns `*_for(p_owner uuid, p_profile_id int, …)`; watched-items wrappers `(p_items jsonb, p_profile_id int)` / pull `(int,int,int)` / cursor `(int)` / delta `(int,bigint,int)` / **delete `(p_profile_id int, p_keys jsonb)`**; library wrappers `(p_items jsonb, p_profile_id int)` / pull `(int,int,int)`. Teardown drops the exact signatures created; ACL revoke/grant signatures match. `watched_at`/`added_at` bigint; `season/episode` int nullable; `imdb_rating` real; `genres` text[]. `nulls not distinct` unique key matches the `on conflict (user_id, profile_id, content_id, season, episode)` target. ✓

**4. Multi-agent verification (2026-06-14, before execution):** A 4-agent workflow ran this plan's assembled SQL on the **real PG17.6 branch** (rolled-back) and statically audited contract/security/spec. **Result: GREEN** — all 16 assert-blocks pass; all four high-risk mechanisms proven on-engine (`UNIQUE NULLS NOT DISTINCT` movie-dedup via col-list `ON CONFLICT`; `genres text[]` → JSON array `["Sci-Fi", "Thriller"]`; `IS NOT DISTINCT FROM` content_id-only movie delete; `imdb_rating real` → JSON number). Contract + security audits returned **zero** findings (all 7 RPCs match `WatchedItemsSyncService.kt`/`LibrarySyncService.kt`/`SupabaseModels.kt`; ACL/RLS/search_path/NULL-owner mirror Plan 1). Two **LOW** findings folded in above: (a) Task 10 genres `jsonb_typeof='array'` guard; (b) Task 3 paging tiebreaker switched to `season/episode asc nulls first`.

> **Operational caveat (harness, not a plan defect):** the verifier observed **non-deterministic DDL leakage past `ROLLBACK`** when running large multi-file `begin;…rollback;` blocks through the **session-mode pgbouncer pooler**. The per-test-file rollback in `run_sync_tests.sh` (one `*_test.sql` per `begin;…rollback;`, as Plan 1 used cleanly) is the supported path; if you ever batch many setup+test files into one transaction, or run teardowns, prefer a dedicated psql session. After execution, sanity-check the branch has no leaked fixture rows (`select count(*) from public.watched_items` etc. should reflect only intended state).

---

## Execution Handoff

Plan complete and saved. Prerequisite: the Plan-1 branch DB + harness (already in place). Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between, fast iteration.
2. **Inline Execution** — executing-plans, batch with checkpoints.
