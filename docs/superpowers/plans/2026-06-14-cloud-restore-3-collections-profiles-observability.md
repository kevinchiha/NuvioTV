# Cloud Restore — Plan 3: Collections / Settings / Profiles + Observability (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy the remaining cloud-restore subsystems — `collections`, `home_catalog_settings`, `profile_settings_blob`, `profiles`/`profile_locks` (spec §5.4/§5.5) — plus the observability/maintenance layer (`prune_sync_events` retention R10, `get_sync_overview` row-count probe §7, a post-deploy RPC-probe script §9, and a deployment runbook §10) so a reinstalled/fresh KevBox TV fully restores its collections, settings, and profiles **with zero client changes**, and so fleet-wide go-live is gated by a detection signal + canary path + runbook.

**Architecture:** Identical owner-scoped pattern to Plans 1 & 2 — every data RPC resolves the caller via the shared `get_sync_owner()` (deployed in Plan 1), is `SECURITY DEFINER set search_path = ''` with an explicit `user_id = nullif(get_sync_owner(),'')::uuid` predicate, and mutating RPCs are thin JWT-resolving wrappers over EXECUTE-revoked explicit-owner inner `*_for(p_owner uuid, …)` functions. Plan-3-specific twists: (1) **collections/home-catalog/profile-settings are snapshot JSON-blob tables** — `*_json` columns are `jsonb` stored **verbatim** (a stringified blob breaks the kotlinx `JsonObject`/`JsonElement` decode); pulls return a `SETOF` the client takes `firstOrNull()` of, and an **empty set is valid**. (2) **`profiles` is the single highest-risk object** — `sync_pull_profiles()` is **no-arg** and called **un-guarded** as the first statement of the startup restore (`StartupSyncService.kt:369`); ANY error it raises aborts the entire broad restore, so it must **never error** and always return a decodable row with a non-null `profile_index` (auto-synthesizing a default `profile_index = 1` row when the member has none). (3) The profile registry keys on **`profile_index`** (not `profile_id`); data-table `profile_id` columns are plain ints, **not** FK'd to `profiles`.

**Tech Stack:** PostgreSQL 15+ (Supabase; branch + prod are PG17), PostgREST RPC. Tests are `psql` assertion scripts run against the **disposable Supabase branch DB** already provisioned for Plans 1–2 (`SYNC_TEST_DB_URL` in the gitignored `.supabase_db.env`, session-mode pooler). Reuses the Plan-1/2 harness verbatim: `run_sync_tests.sh` + `sync_test_helpers.sql` (`test_login`/`test_logout`), each `*_test.sql` wrapped in `begin … rollback`.

**Spec:** `docs/superpowers/specs/2026-06-14-cloud-restore-design.md` (§4 R1–R10, §5.4, §5.5, §7, §8, §9, §10). Requirement IDs referenced inline.

**Depends on:** Plan 1 + Plan 2 (deployed + green on the branch): `get_sync_owner_setup.sql`, `watch_progress_setup.sql`, `watched_items_setup.sql`, `library_setup.sql`, `sync_test_helpers.sql`, `run_sync_tests.sh`. `prune_sync_events`, `get_sync_overview`, `sync_delete_profile_data`, and the post-deploy probe reference the Plan-1/2 tables, so on the branch those setups must be applied first (the test run-commands below include them; the runbook fixes deploy order).

**Verified client contract** (read 2026-06-14 from `CollectionSyncService.kt`, `HomeCatalogSettingsSyncService.kt`, `ProfileSettingsSyncService.kt`, `ProfileSyncService.kt`, `AccountViewModel.kt`, `SupabaseModels.kt`; PostgREST binds by **name**):

| RPC | SQL signature (exact) | Decode target |
|---|---|---|
| `sync_push_collections` | `(p_profile_id int, p_collections_json jsonb)` | — |
| `sync_pull_collections` | `(p_profile_id int)` | `decodeList<SupabaseCollectionBlob>().firstOrNull()` |
| `sync_push_home_catalog_settings` | `(p_profile_id int, p_settings_json jsonb, p_platform text)` | — |
| `sync_pull_home_catalog_settings` | `(p_profile_id int, p_platform text)` | `decodeList<SupabaseHomeCatalogSettingsBlob>().firstOrNull()` (called ×3: `home_catalog_shared`, `tv`, `mobile`) |
| `sync_push_profile_settings_blob` | `(p_profile_id int, p_settings_json jsonb, p_platform text)` | — |
| `sync_pull_profile_settings_blob` | `(p_profile_id int, p_platform text)` | `decodeList<SupabaseProfileSettingsBlob>()` then `firstOrNull()?.settingsJson["features"]` (platform `tv`) |
| `sync_pull_profiles` | `()` — **NO ARGS** | `decodeList<SupabaseProfile>()` |
| `sync_pull_profile_locks` | `()` — **NO ARGS** | `decodeList<SupabaseProfileLockState>()` |
| `sync_push_profiles` | `(p_client_max_profiles int, p_profiles jsonb)` | — |
| `sync_delete_profile_data` | `(p_profile_id int)` | — |
| `get_sync_overview` | `()` — **NO ARGS** | `decodeAs<SyncOverviewResponse>()` (single object; `runCatching` fail-soft) |

**Decode-model required (non-default, non-nullable) fields** — kotlinx throws otherwise:
- `SupabaseCollectionBlob`: **none** (all default). Keys: `profile_id`(Int=1), `collections_json`(JsonElement=`[]`), `updated_at`(String?).
- `SupabaseHomeCatalogSettingsBlob`: **none**. Keys: `profile_id`(Int=1), `settings_json`(JsonObject=`{}`), `updated_at`(String?).
- `SupabaseProfileSettingsBlob`: **none**. Keys: `profile_id`(Int=1), `settings_json`(JsonObject=`{}`), `updated_at`(String?).
- `SupabaseProfile`: **`profile_index`(Int)** is the only required field. Other keys: `id`(String?), `user_id`(String?), `name`(=''), `avatar_color_hex`(='#1E88E5'), `uses_primary_addons`(=false), `uses_primary_plugins`(=false), `avatar_id`(String?), `avatar_url`(String?), `created_at`(String?), `updated_at`(String?).
- `SupabaseProfileLockState`: **`profile_index`(Int)** required. `pin_enabled`(=false), `pin_locked_until`(String?).
- `SyncOverviewResponse`: **none** (all default). Keys: `addons`/`plugins`/`library_items`/`watch_progress`/`watched_items`(Map<String,Int>=`{}`), `profiles`(Map<String,{name,color}>=`{}`).

**Push payload keys (verbatim from client):**
- collections `p_collections_json`: a JSON **array** root (or `[]` when local is empty).
- home-catalog `p_settings_json`: a JSON **object** (`{hide_unreleased_content, items[...]}`); push platform `"home_catalog_shared"`.
- profile-settings `p_settings_json`: a JSON **object** `{version:int, features:{<feature>:{<key>:{type,value}}}}` — must round-trip **verbatim** (client reads `blob["features"].jsonObject`); push platform `"tv"`.
- `p_profiles[]`: `{profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins, avatar_id?, avatar_url?}`.

---

## File Structure

All SQL files at **repo root** (matches Plans 1–2 + `member_*_setup.sql`). Test artifacts at root (`.gitignore` ignores `scripts/*`). The runbook + probe script are deliverables too.

- `collections_setup.sql` / `collections_teardown.sql` / `collections_test.sql`
- `home_catalog_settings_setup.sql` / `home_catalog_settings_teardown.sql` / `home_catalog_settings_test.sql`
- `profile_settings_blob_setup.sql` / `profile_settings_blob_teardown.sql` / `profile_settings_blob_test.sql`
- `profiles_setup.sql` / `profiles_teardown.sql` / `profiles_test.sql` — `profiles` + `profile_locks` tables, 4 RPCs (incl. cross-subsystem `sync_delete_profile_data`).
- `sync_maintenance_setup.sql` / `sync_maintenance_teardown.sql` / `sync_maintenance_test.sql` — `prune_sync_events` (R10) + `get_sync_overview` (§7/§8).
- `probe_sync_rpcs.sql` — post-deploy RPC-probe (§9 detection).
- `CLOUD-RESTORE-RUNBOOK.md` (repo root, mirrors KevBox `MEMBER-*` doc convention) — deploy order, canary path, data-destructive-teardown caveat (§8/§9/§10).

> **Deploy vs test:** on the live project run ONLY the `*_setup.sql` files (after `get_sync_owner_setup.sql` + Plan 1/2 setups, in the runbook order). On the branch, `run_sync_tests.sh` applies `sync_test_helpers.sql`, then the setup files, then the `*_test.sql` files (the latter rolled back).

> **Branch-DB testing model (same as Plans 1–2):** the branch already has the real Supabase `auth` surface + `get_sync_owner` + watch_progress/watched_items/library (from Plans 1–2). `*_setup.sql` is applied for real (persists = deploy-to-branch); `*_test.sql` runs in one rolled-back transaction, so **every test DO block must set its own session at entry** (`test_login(...)`/`test_logout()`) — `set_config(..., true)` is transaction-local.

> **Roles caveat (verified on the branch):** the branch DB has only `anon`, `authenticated`, `postgres`, `service_role` — **no `kevbox_admin`**. So `prune_sync_events` is **NOT** granted to `kevbox_admin` (unlike `prune_telemetry`); it is locked to the definer/cron path (revoked from all app roles). This keeps the setup file runnable on both branch and prod.

---

## Task 1: `collections` schema + push/pull RPCs + ACLs

**Files:**
- Create: `collections_setup.sql`
- Create: `collections_test.sql`

- [ ] **Step 1: Write the failing schema+RPC test**

Create `collections_test.sql`:

```sql
-- ============ collections: schema ============
do $$
begin
  assert to_regclass('public.collections') is not null, 'collections table missing';
  -- R3: PK = ON CONFLICT target (user_id, profile_id).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.collections'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.collections'::regclass and attnum=any(conkey))
          = array['profile_id','user_id']
  ), 'collections PK must be (user_id, profile_id)';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.collections'::regclass and attname='collections_json') = 'jsonb',
    'collections_json must be jsonb (stringified blob breaks decode)';
  assert (select relrowsecurity from pg_class where oid='public.collections'::regclass), 'RLS off on collections';
  assert not has_table_privilege('authenticated','public.collections','INSERT'), 'authenticated can INSERT collections';
  assert not has_table_privilege('authenticated','public.collections','UPDATE'), 'authenticated can UPDATE collections';
  assert not has_table_privilege('authenticated','public.collections','DELETE'), 'authenticated can DELETE collections';
  assert has_table_privilege('authenticated','public.collections','SELECT'), 'authenticated needs RLS-scoped SELECT';
  assert not has_table_privilege('anon','public.collections','SELECT'), 'anon must not SELECT collections';
  raise notice 'collections schema OK';
end $$;

-- ============ collections: push/pull (R1 isolation, R7 shape, JSON array verbatim, empty-set) ============
do $$
declare a uuid := '11111111-1111-cccc-1111-111111111111';
        b uuid := '22222222-2222-cccc-2222-222222222222';
        v_shape text; v_json jsonb; n int;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  -- empty pull before any push must return ZERO rows (client preserves local), not error.
  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_collections(1)) = 0, 'empty collections pull must be 0 rows';

  -- push a JSON ARRAY blob; pull it back verbatim.
  perform public.sync_push_collections(1, jsonb_build_array(
    jsonb_build_object('id','c1','name','Faves'),
    jsonb_build_object('id','c2','name','Later')));
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','b1','name','B')));  -- noise as... still a
  perform public.test_login(b);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','bonly','name','Bonly')));

  -- R1: member B pulls only B's row.
  perform public.test_login(b);
  select count(*) into n from public.sync_pull_collections(1);
  assert n = 1, format('B should pull 1 row, got %s', n);
  select collections_json into v_json from public.sync_pull_collections(1);
  assert v_json = jsonb_build_array(jsonb_build_object('id','bonly','name','Bonly')),
    format('B must see only its own collections; got %s', v_json::text);

  -- R3: re-push for A overwrites the SAME row (snapshot last-write-wins).
  perform public.test_login(a);
  select count(*) into n from public.sync_pull_collections(1);
  assert n = 1, format('A must have exactly 1 row after re-push, got %s', n);
  select collections_json into v_json from public.sync_pull_collections(1);
  assert v_json = jsonb_build_array(jsonb_build_object('id','b1','name','B')),
    format('A re-push must overwrite; got %s', v_json::text);
  -- the root must stay a JSON ARRAY (decodes to JsonElement/JsonArray).
  assert jsonb_typeof(v_json) = 'array', format('collections_json must serialize as a JSON array; got %s', jsonb_typeof(v_json));

  -- R7 wire-shape: pulled row JSON keys = exactly SupabaseCollectionBlob's emitted set (no user_id).
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_collections(1) limit 1 ) t ) s;
  assert v_shape = 'collections_json,profile_id,updated_at',
    format('pull row JSON keys must match SupabaseCollectionBlob exactly; got: %s', v_shape);

  raise notice 'collections push/pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `source .supabase_db.env && ./run_sync_tests.sh get_sync_owner_setup.sql collections_test.sql`
Expected: FAIL with `collections table missing`.

- [ ] **Step 3: Write `collections_setup.sql`**

Create `collections_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql collections_setup.sql collections_test.sql`
Expected: PASS — `ALL SYNC SQL TESTS PASSED`.

- [ ] **Step 5: Commit**

```bash
git add collections_setup.sql collections_test.sql
git commit -m "feat(sync): collections snapshot (jsonb array verbatim, push/pull, RLS, ACLs)"
```

---

## Task 2: `collections` ACL + NULL-owner + RLS read-own + teardown + idempotency

**Files:**
- Create: `collections_teardown.sql`
- Modify: `collections_test.sql`

- [ ] **Step 1: Append the ACL + NULL-owner + RLS + idempotency tests**

Append to `collections_test.sql`:

```sql
-- ============ collections: function ACLs + NULL-owner + RLS read-own ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_collections_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_collections(int,jsonb)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_collections(int)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  raise notice 'collections ACLs OK';
end $$;

-- RLS read-own.
do $$
declare a uuid := 'aaaaaaaa-1111-cccc-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-1111-cccc-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','rls_a')));
  perform public.test_login(b);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','rls_b')));
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.collections where collections_json @> jsonb_build_array(jsonb_build_object('id','rls_b'))) = 0,
    'RLS must hide member B''s collections from A';
  assert (select count(*) from public.collections where collections_json @> jsonb_build_array(jsonb_build_object('id','rls_a'))) >= 1,
    'A must see its own collections';
  raise notice 'collections RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4).
do $$
declare before_rows int; after_rows int;
begin
  perform public.test_logout();
  select count(*) into before_rows from public.collections;
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','n')));
  select count(*) into after_rows from public.collections;
  assert after_rows = before_rows, 'anon push must not change row count';
  assert not exists (select 1 from public.collections where user_id is null), 'no NULL-user_id rows';
  assert (select count(*) from public.sync_pull_collections(1)) = 0, 'anon pull must be empty';
  raise notice 'collections NULL-owner OK';
end $$;

-- ============ collections: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-1111-cccc-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','idemp')));
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line — psql parses a trailing
-- inline comment as extra \i arguments and emits noisy "extra argument ignored" warnings).
\i collections_setup.sql

do $$
declare s uuid := 'ffffffff-1111-cccc-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.collections where user_id=s and profile_id=1) = 1,
    're-applying setup must PRESERVE existing rows';
  raise notice 'collections idempotency OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql collections_setup.sql collections_test.sql`
Expected: PASS.

- [ ] **Step 3: Write `collections_teardown.sql`**

Create `collections_teardown.sql`:

```sql
-- Rollback of collections_setup.sql. Removes ONLY collections objects. Does NOT touch get_sync_owner
-- (shared), auth.users, watch_progress, watched_items, library, profiles, member_*, or telemetry.
-- DROPS the stored collections data. Idempotent.

drop function if exists public.sync_push_collections(int, jsonb);
drop function if exists public.sync_push_collections_for(uuid, int, jsonb);
drop function if exists public.sync_pull_collections(int);

drop table if exists public.collections;

select 'collections' as obj, to_regclass('public.collections') as still_exists;
```

- [ ] **Step 4: Verify teardown drops cleanly, then restore the branch**

```bash
./run_sync_tests.sh get_sync_owner_setup.sql collections_setup.sql collections_teardown.sql
./run_sync_tests.sh get_sync_owner_setup.sql collections_setup.sql
psql "$SYNC_TEST_DB_URL" -At -c "select 'collections='||coalesce(to_regclass('public.collections')::text,'MISSING')"
```
Expected: teardown sanity `select` shows `collections` gone; re-apply exits 0; final check prints `collections=collections`.

- [ ] **Step 5: Commit**

```bash
git add collections_teardown.sql collections_test.sql
git commit -m "feat(sync): collections ACL + NULL-owner + RLS tests, teardown + idempotency"
```

---

## Task 3: `home_catalog_settings` schema + push/pull (multi-platform) + ACLs

**Files:**
- Create: `home_catalog_settings_setup.sql`
- Create: `home_catalog_settings_test.sql`

- [ ] **Step 1: Write the failing schema+RPC test**

Create `home_catalog_settings_test.sql`:

```sql
-- ============ home_catalog_settings: schema ============
do $$
begin
  assert to_regclass('public.home_catalog_settings') is not null, 'home_catalog_settings table missing';
  -- R3: PK = ON CONFLICT target (user_id, profile_id, platform).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.home_catalog_settings'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.home_catalog_settings'::regclass and attnum=any(conkey))
          = array['platform','profile_id','user_id']
  ), 'home_catalog_settings PK must be (user_id, profile_id, platform)';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.home_catalog_settings'::regclass and attname='settings_json') = 'jsonb',
    'settings_json must be jsonb';
  assert (select relrowsecurity from pg_class where oid='public.home_catalog_settings'::regclass), 'RLS off';
  assert not has_table_privilege('authenticated','public.home_catalog_settings','INSERT'), 'authenticated can INSERT';
  assert has_table_privilege('authenticated','public.home_catalog_settings','SELECT'), 'authenticated needs SELECT';
  assert not has_table_privilege('anon','public.home_catalog_settings','SELECT'), 'anon must not SELECT';
  raise notice 'home_catalog_settings schema OK';
end $$;

-- ============ home_catalog_settings: push/pull (R1, R7 shape, JSON object, multi-platform, empty-set) ============
do $$
declare a uuid := '11111111-1111-dddd-1111-111111111111';
        b uuid := '22222222-2222-dddd-2222-222222222222';
        v_shape text; v_json jsonb; n int;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  -- absent platform must return ZERO rows (the client pulls 3 platforms; missing must NOT error).
  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_home_catalog_settings(1,'tv')) = 0, 'absent platform must be 0 rows';

  -- push the shared platform; pull it back verbatim (JSON OBJECT).
  perform public.sync_push_home_catalog_settings(1,
    jsonb_build_object('hide_unreleased_content', true, 'items', jsonb_build_array('x','y')),
    'home_catalog_shared');
  -- a DIFFERENT platform for the SAME profile is a DISTINCT row.
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('items', jsonb_build_array('m')), 'mobile');

  perform public.test_login(b);
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('items', jsonb_build_array('bonly')), 'home_catalog_shared');

  -- R1 + per-platform isolation: A pulls only A's shared row; A's two platforms are distinct.
  perform public.test_login(a);
  select count(*) into n from public.sync_pull_home_catalog_settings(1,'home_catalog_shared');
  assert n = 1, format('A shared pull should be 1 row, got %s', n);
  select settings_json into v_json from public.sync_pull_home_catalog_settings(1,'home_catalog_shared');
  assert v_json = jsonb_build_object('hide_unreleased_content', true, 'items', jsonb_build_array('x','y')),
    format('A shared blob mismatch; got %s', v_json::text);
  assert jsonb_typeof(v_json) = 'object', 'settings_json must serialize as a JSON object';
  assert (select settings_json from public.sync_pull_home_catalog_settings(1,'mobile'))
         = jsonb_build_object('items', jsonb_build_array('m')), 'mobile platform must be its own row';

  -- R3: re-push shared overwrites the SAME (user,profile,platform) row.
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('items', jsonb_build_array('z')), 'home_catalog_shared');
  assert (select count(*) from public.sync_pull_home_catalog_settings(1,'home_catalog_shared')) = 1, 're-push must not duplicate';
  assert (select settings_json from public.sync_pull_home_catalog_settings(1,'home_catalog_shared'))
         = jsonb_build_object('items', jsonb_build_array('z')), 're-push must overwrite';

  -- R7 wire-shape: exact SupabaseHomeCatalogSettingsBlob keys (no user_id, no platform).
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_home_catalog_settings(1,'home_catalog_shared') limit 1 ) t ) s;
  assert v_shape = 'profile_id,settings_json,updated_at',
    format('pull row JSON keys must match SupabaseHomeCatalogSettingsBlob exactly; got: %s', v_shape);

  raise notice 'home_catalog_settings push/pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql home_catalog_settings_test.sql`
Expected: FAIL with `home_catalog_settings table missing`.

- [ ] **Step 3: Write `home_catalog_settings_setup.sql`**

Create `home_catalog_settings_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql home_catalog_settings_setup.sql home_catalog_settings_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add home_catalog_settings_setup.sql home_catalog_settings_test.sql
git commit -m "feat(sync): home_catalog_settings snapshot (per-platform jsonb, push/pull, RLS, ACLs)"
```

---

## Task 4: `home_catalog_settings` ACL + NULL-owner + RLS + teardown + idempotency

**Files:**
- Create: `home_catalog_settings_teardown.sql`
- Modify: `home_catalog_settings_test.sql`

- [ ] **Step 1: Append the ACL + NULL-owner + RLS + idempotency tests**

Append to `home_catalog_settings_test.sql`:

```sql
-- ============ home_catalog_settings: function ACLs + NULL-owner + RLS read-own ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_home_catalog_settings_for(uuid,int,jsonb,text)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_home_catalog_settings(int,jsonb,text)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_home_catalog_settings(int,text)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  raise notice 'home_catalog_settings ACLs OK';
end $$;

-- RLS read-own.
do $$
declare a uuid := 'aaaaaaaa-1111-dddd-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-1111-dddd-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('k','rls_a'), 'tv');
  perform public.test_login(b);
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('k','rls_b'), 'tv');
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.home_catalog_settings where settings_json->>'k'='rls_b') = 0,
    'RLS must hide member B''s settings from A';
  assert (select count(*) from public.home_catalog_settings where settings_json->>'k'='rls_a') >= 1,
    'A must see its own settings';
  raise notice 'home_catalog_settings RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4).
do $$
declare before_rows int; after_rows int;
begin
  perform public.test_logout();
  select count(*) into before_rows from public.home_catalog_settings;
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('k','n'), 'tv');
  select count(*) into after_rows from public.home_catalog_settings;
  assert after_rows = before_rows, 'anon push must not change row count';
  assert not exists (select 1 from public.home_catalog_settings where user_id is null), 'no NULL-user_id rows';
  assert (select count(*) from public.sync_pull_home_catalog_settings(1,'tv')) = 0, 'anon pull must be empty';
  raise notice 'home_catalog_settings NULL-owner OK';
end $$;

-- ============ home_catalog_settings: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-1111-dddd-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object('k','idemp'), 'tv');
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line).
\i home_catalog_settings_setup.sql

do $$
declare s uuid := 'ffffffff-1111-dddd-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.home_catalog_settings where user_id=s and platform='tv') = 1,
    're-applying setup must PRESERVE existing rows';
  raise notice 'home_catalog_settings idempotency OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql home_catalog_settings_setup.sql home_catalog_settings_test.sql`
Expected: PASS.

- [ ] **Step 3: Write `home_catalog_settings_teardown.sql`**

Create `home_catalog_settings_teardown.sql`:

```sql
-- Rollback of home_catalog_settings_setup.sql. Removes ONLY home_catalog_settings objects. Does NOT
-- touch get_sync_owner (shared), auth.users, or any sibling subsystem. DROPS the stored data. Idempotent.

drop function if exists public.sync_push_home_catalog_settings(int, jsonb, text);
drop function if exists public.sync_push_home_catalog_settings_for(uuid, int, jsonb, text);
drop function if exists public.sync_pull_home_catalog_settings(int, text);

drop table if exists public.home_catalog_settings;

select 'home_catalog_settings' as obj, to_regclass('public.home_catalog_settings') as still_exists;
```

- [ ] **Step 4: Verify teardown drops cleanly, then restore the branch**

```bash
./run_sync_tests.sh get_sync_owner_setup.sql home_catalog_settings_setup.sql home_catalog_settings_teardown.sql
./run_sync_tests.sh get_sync_owner_setup.sql home_catalog_settings_setup.sql
psql "$SYNC_TEST_DB_URL" -At -c "select 'hcs='||coalesce(to_regclass('public.home_catalog_settings')::text,'MISSING')"
```
Expected: teardown clean; re-apply exits 0; final check prints `hcs=home_catalog_settings`.

- [ ] **Step 5: Commit**

```bash
git add home_catalog_settings_teardown.sql home_catalog_settings_test.sql
git commit -m "feat(sync): home_catalog_settings ACL + NULL-owner + RLS tests, teardown + idempotency"
```

---

## Task 5: `profile_settings_blob` schema + push/pull (features round-trip) + ACLs

**Files:**
- Create: `profile_settings_blob_setup.sql`
- Create: `profile_settings_blob_test.sql`

> **Why a separate table from home-catalog:** both hold a `"tv"` row but are distinct subsystems (§5.4). `profile_settings_blob.settings_json` is the nested `{version, features:{…}}` object; the client reads `blob["features"].jsonObject` and silently no-ops settings restore if `features` is absent — so the blob must round-trip **verbatim**.

- [ ] **Step 1: Write the failing schema+RPC test**

Create `profile_settings_blob_test.sql`:

```sql
-- ============ profile_settings_blob: schema ============
do $$
begin
  assert to_regclass('public.profile_settings_blob') is not null, 'profile_settings_blob table missing';
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profile_settings_blob'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profile_settings_blob'::regclass and attnum=any(conkey))
          = array['platform','profile_id','user_id']
  ), 'profile_settings_blob PK must be (user_id, profile_id, platform)';
  assert (select atttypid::regtype::text from pg_attribute
          where attrelid='public.profile_settings_blob'::regclass and attname='settings_json') = 'jsonb',
    'settings_json must be jsonb';
  assert (select relrowsecurity from pg_class where oid='public.profile_settings_blob'::regclass), 'RLS off';
  assert not has_table_privilege('authenticated','public.profile_settings_blob','INSERT'), 'authenticated can INSERT';
  assert has_table_privilege('authenticated','public.profile_settings_blob','SELECT'), 'authenticated needs SELECT';
  assert not has_table_privilege('anon','public.profile_settings_blob','SELECT'), 'anon must not SELECT';
  raise notice 'profile_settings_blob schema OK';
end $$;

-- ============ profile_settings_blob: push/pull (R1, R7 shape, nested features round-trip verbatim) ============
do $$
declare a uuid := '11111111-1111-eeee-1111-111111111111';
        b uuid := '22222222-2222-eeee-2222-222222222222';
        v_shape text; v_json jsonb; blob jsonb;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  -- the nested {version, features:{...}} blob the client expects.
  blob := jsonb_build_object(
    'version', 1,
    'features', jsonb_build_object(
      'player', jsonb_build_object(
        'forced_subs', jsonb_build_object('type','boolean','value', false),
        'sub_lang',    jsonb_build_object('type','string','value','en'))));

  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 0, 'absent blob must be 0 rows';
  perform public.sync_push_profile_settings_blob(1, blob, 'tv');
  perform public.test_login(b);
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',1,'features',jsonb_build_object('x',jsonb_build_object())), 'tv');

  -- R1 + verbatim round-trip: A's features sub-object must come back byte-equal.
  perform public.test_login(a);
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 1, 'A must have 1 tv blob';
  select settings_json into v_json from public.sync_pull_profile_settings_blob(1,'tv');
  assert v_json = blob, format('blob must round-trip verbatim; got %s', v_json::text);
  assert v_json -> 'features' is not null, 'features sub-object must be present (client no-ops if absent)';
  assert v_json #> '{features,player,sub_lang,value}' = '"en"'::jsonb, 'nested feature value must survive verbatim';

  -- R3: re-push overwrites the SAME (user,profile,platform) row.
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',2,'features',jsonb_build_object()), 'tv');
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 1, 're-push must not duplicate';
  assert (select settings_json->>'version' from public.sync_pull_profile_settings_blob(1,'tv')) = '2', 're-push must overwrite';

  -- R7 wire-shape.
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_profile_settings_blob(1,'tv') limit 1 ) t ) s;
  assert v_shape = 'profile_id,settings_json,updated_at',
    format('pull row JSON keys must match SupabaseProfileSettingsBlob exactly; got: %s', v_shape);

  raise notice 'profile_settings_blob push/pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profile_settings_blob_test.sql`
Expected: FAIL with `profile_settings_blob table missing`.

- [ ] **Step 3: Write `profile_settings_blob_setup.sql`**

Create `profile_settings_blob_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profile_settings_blob_setup.sql profile_settings_blob_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add profile_settings_blob_setup.sql profile_settings_blob_test.sql
git commit -m "feat(sync): profile_settings_blob snapshot (verbatim features round-trip, push/pull, RLS, ACLs)"
```

---

## Task 6: `profile_settings_blob` ACL + NULL-owner + RLS + teardown + idempotency

**Files:**
- Create: `profile_settings_blob_teardown.sql`
- Modify: `profile_settings_blob_test.sql`

- [ ] **Step 1: Append the ACL + NULL-owner + RLS + idempotency tests**

Append to `profile_settings_blob_test.sql`:

```sql
-- ============ profile_settings_blob: function ACLs + NULL-owner + RLS read-own ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_profile_settings_blob_for(uuid,int,jsonb,text)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_profile_settings_blob(int,jsonb,text)', 'EXECUTE'),
    'push wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_profile_settings_blob(int,text)', 'EXECUTE'),
    'pull wrapper must be executable by authenticated';
  raise notice 'profile_settings_blob ACLs OK';
end $$;

-- RLS read-own.
do $$
declare a uuid := 'aaaaaaaa-1111-eeee-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-1111-eeee-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('k','rls_a'), 'tv');
  perform public.test_login(b);
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('k','rls_b'), 'tv');
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.profile_settings_blob where settings_json->>'k'='rls_b') = 0,
    'RLS must hide member B''s blob from A';
  assert (select count(*) from public.profile_settings_blob where settings_json->>'k'='rls_a') >= 1,
    'A must see its own blob';
  raise notice 'profile_settings_blob RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4).
do $$
declare before_rows int; after_rows int;
begin
  perform public.test_logout();
  select count(*) into before_rows from public.profile_settings_blob;
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('k','n'), 'tv');
  select count(*) into after_rows from public.profile_settings_blob;
  assert after_rows = before_rows, 'anon push must not change row count';
  assert not exists (select 1 from public.profile_settings_blob where user_id is null), 'no NULL-user_id rows';
  assert (select count(*) from public.sync_pull_profile_settings_blob(1,'tv')) = 0, 'anon pull must be empty';
  raise notice 'profile_settings_blob NULL-owner OK';
end $$;

-- ============ profile_settings_blob: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-1111-eeee-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('k','idemp'), 'tv');
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line).
\i profile_settings_blob_setup.sql

do $$
declare s uuid := 'ffffffff-1111-eeee-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.profile_settings_blob where user_id=s and platform='tv') = 1,
    're-applying setup must PRESERVE existing rows';
  raise notice 'profile_settings_blob idempotency OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profile_settings_blob_setup.sql profile_settings_blob_test.sql`
Expected: PASS.

- [ ] **Step 3: Write `profile_settings_blob_teardown.sql`**

Create `profile_settings_blob_teardown.sql`:

```sql
-- Rollback of profile_settings_blob_setup.sql. Removes ONLY profile_settings_blob objects. Does NOT
-- touch get_sync_owner (shared), auth.users, or any sibling subsystem. DROPS the stored data. Idempotent.

drop function if exists public.sync_push_profile_settings_blob(int, jsonb, text);
drop function if exists public.sync_push_profile_settings_blob_for(uuid, int, jsonb, text);
drop function if exists public.sync_pull_profile_settings_blob(int, text);

drop table if exists public.profile_settings_blob;

select 'profile_settings_blob' as obj, to_regclass('public.profile_settings_blob') as still_exists;
```

- [ ] **Step 4: Verify teardown drops cleanly, then restore the branch**

```bash
./run_sync_tests.sh get_sync_owner_setup.sql profile_settings_blob_setup.sql profile_settings_blob_teardown.sql
./run_sync_tests.sh get_sync_owner_setup.sql profile_settings_blob_setup.sql
psql "$SYNC_TEST_DB_URL" -At -c "select 'psb='||coalesce(to_regclass('public.profile_settings_blob')::text,'MISSING')"
```
Expected: teardown clean; re-apply exits 0; final check prints `psb=profile_settings_blob`.

- [ ] **Step 5: Commit**

```bash
git add profile_settings_blob_teardown.sql profile_settings_blob_test.sql
git commit -m "feat(sync): profile_settings_blob ACL + NULL-owner + RLS tests, teardown + idempotency"
```

---

## Task 7: `profiles` + `profile_locks` schema (RLS, grants)

**Files:**
- Create: `profiles_setup.sql`
- Create: `profiles_test.sql`

> **Highest-risk subsystem.** `sync_pull_profiles()` is no-arg and un-guarded on the startup path (§5.5). The key column is **`profile_index`** (not `profile_id`). `profile_id` columns on the data tables are plain ints, NOT FK'd to `profiles`.

- [ ] **Step 1: Write the failing schema test**

Create `profiles_test.sql`:

```sql
-- ============ profiles + profile_locks: schema ============
do $$
begin
  assert to_regclass('public.profiles') is not null, 'profiles table missing';
  assert to_regclass('public.profile_locks') is not null, 'profile_locks table missing';

  -- R3: profiles PK = (user_id, profile_index); profile_locks PK = (user_id, profile_index).
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profiles'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profiles'::regclass and attnum=any(conkey))
          = array['profile_index','user_id']
  ), 'profiles PK must be (user_id, profile_index)';
  assert exists (
    select 1 from pg_constraint
    where conrelid='public.profile_locks'::regclass and contype='p'
      and (select array_agg(attname::text order by attname::text)
           from pg_attribute where attrelid='public.profile_locks'::regclass and attnum=any(conkey))
          = array['profile_index','user_id']
  ), 'profile_locks PK must be (user_id, profile_index)';

  -- profile_index is a non-null int (the one required model field).
  assert (select attnotnull from pg_attribute where attrelid='public.profiles'::regclass and attname='profile_index'),
    'profiles.profile_index must be NOT NULL';

  assert (select relrowsecurity from pg_class where oid='public.profiles'::regclass), 'RLS off on profiles';
  assert (select relrowsecurity from pg_class where oid='public.profile_locks'::regclass), 'RLS off on profile_locks';
  assert not has_table_privilege('authenticated','public.profiles','INSERT'), 'authenticated can INSERT profiles';
  assert has_table_privilege('authenticated','public.profiles','SELECT'), 'authenticated needs SELECT on profiles';
  assert not has_table_privilege('anon','public.profiles','SELECT'), 'anon must not SELECT profiles';
  raise notice 'profiles schema OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profiles_test.sql`
Expected: FAIL with `profiles table missing`.

- [ ] **Step 3: Write the schema (start `profiles_setup.sql`)**

Create `profiles_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profiles_setup.sql profiles_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add profiles_setup.sql profiles_test.sql
git commit -m "feat(sync): profiles + profile_locks schema (profile_index key, RLS, grants)"
```

---

## Task 8: `sync_pull_profiles` (no-arg, auto-default, never-error) + `sync_pull_profile_locks`

**Files:**
- Modify: `profiles_setup.sql`
- Modify: `profiles_test.sql`

- [ ] **Step 1: Append the failing pull tests (T-PROFILE: un-guarded pull must resolve for a brand-new account)**

Append to `profiles_test.sql`:

```sql
-- ============ profiles: pull (no-arg, auto-default, never-error; R1; R7 non-null profile_index) ============
-- T-PROFILE (§5.5): a brand-new member with ZERO profiles rows must still get a decodable row with a
-- non-null profile_index, or the un-guarded startup pull aborts the entire broad restore.
do $$
declare nw uuid := '11111111-1111-aaaa-aaaa-111111111111';
        a  uuid := '22222222-2222-aaaa-aaaa-222222222222';
        b  uuid := '33333333-3333-aaaa-aaaa-333333333333';
        n int; v_idx int; v_shape text;
begin
  insert into auth.users(id) values (nw),(a),(b) on conflict do nothing;

  -- brand-new member: no stored rows => exactly ONE synthesized default profile_index=1.
  perform public.test_login(nw);
  select count(*) into n from public.sync_pull_profiles();
  assert n = 1, format('brand-new member must get 1 synthesized default profile, got %s', n);
  select profile_index into v_idx from public.sync_pull_profiles();
  assert v_idx = 1, format('synthesized default profile_index must be 1, got %s', v_idx);

  -- with stored rows present, returns them (no synthesized default). Seed via direct INSERT (the
  -- *_test.sql runs as the connection owner, which bypasses the authenticated-only DML revoke) so this
  -- task is independently red->green without depending on sync_push_profiles (added in Task 9).
  insert into public.profiles(user_id, profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins)
  values (nw, 1, 'Main', '#111111', true, false),
         (nw, 2, 'Kids', '#222222', false, false);
  select count(*) into n from public.sync_pull_profiles();
  assert n = 2, format('with stored rows, must return 2 profiles (no synth default), got %s', n);
  assert exists (select 1 from public.sync_pull_profiles() where profile_index=2 and name='Kids'), 'stored profile must round-trip';

  -- R1: member B (no profiles) gets only its own synthesized default, never A's rows.
  perform public.test_login(b);
  select count(*) into n from public.sync_pull_profiles();
  assert n = 1, format('B must see only its own synth default, got %s', n);
  assert not exists (select 1 from public.sync_pull_profiles() where name='Kids'), 'B must not see A''s profiles';

  -- R7 wire-shape: exact SupabaseProfile emitted set (11 keys).
  perform public.test_login(a);
  select string_agg(k, ',' order by k) into v_shape
  from ( select jsonb_object_keys(to_jsonb(t)) as k
         from ( select * from public.sync_pull_profiles() limit 1 ) t ) s;
  assert v_shape = 'avatar_color_hex,avatar_id,avatar_url,created_at,id,name,profile_index,updated_at,user_id,uses_primary_addons,uses_primary_plugins',
    format('pull row JSON keys must match SupabaseProfile exactly; got: %s', v_shape);

  raise notice 'profiles pull OK';
end $$;

-- ============ profile_locks: pull (no-arg; R1; non-null profile_index) ============
do $$
declare a uuid := '44444444-4444-aaaa-aaaa-444444444444';
        n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);
  -- empty is safe (fail-soft; only affects PIN state).
  assert (select count(*) from public.sync_pull_profile_locks()) = 0, 'empty profile_locks pull must be 0 rows';
  raise notice 'profile_locks pull OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profiles_setup.sql profiles_test.sql`
Expected: FAIL — `function public.sync_pull_profiles() does not exist` (or `sync_push_profiles` / `sync_pull_profile_locks`; whichever the test hits first — all are added here + Task 9).

- [ ] **Step 3: Append the pull functions (+ grants)**

Append to `profiles_setup.sql`:

```sql
-- 3. Pull profiles. NO ARGS. SECURITY DEFINER + owner predicate (R1). Exact SupabaseProfile shape (R7,
--    11 keys). CRITICAL (§5.5): the un-guarded startup pull must NEVER error and must always return a
--    row with a non-null profile_index — synthesize a default profile_index=1 row when the owner has none.
create or replace function public.sync_pull_profiles()
returns table(
  id text, user_id text, profile_index int, name text, avatar_color_hex text,
  uses_primary_addons boolean, uses_primary_plugins boolean,
  avatar_id text, avatar_url text, created_at timestamptz, updated_at timestamptz
) language sql security definer set search_path = '' as $$
  with o as (select nullif(public.get_sync_owner(),'')::uuid as uid),
  stored as (
    select null::text as id, p.user_id::text as user_id, p.profile_index, p.name, p.avatar_color_hex,
           p.uses_primary_addons, p.uses_primary_plugins, p.avatar_id, p.avatar_url,
           p.created_at, p.updated_at
    from public.profiles p, o
    where p.user_id = o.uid
  )
  select * from stored
  union all
  -- synthesized default ONLY when the owner has no stored profiles (keeps the un-guarded pull non-empty
  -- and decodable; profile_index=1 is the client's default). Works even for a null owner (returns a
  -- harmless default row) so the call never raises.
  select null::text, (select uid::text from o), 1, ''::text, '#1E88E5'::text,
         false, false, null::text, null::text, now(), now()
  where not exists (select 1 from stored)
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
```

> Note: the test in Step 1 calls `sync_push_profiles` (added in Task 9). If you are executing strictly task-by-task, Step 4 below will still report the push function missing until Task 9 lands. To keep this task self-contained and GREEN, **also append the Task-9 push/delete functions now** (they are listed in Task 9 Step 3) — or run the combined GREEN check at the end of Task 9. The recommended flow: implement Task 8 + Task 9 setup additions together, then run the full `profiles_test.sql` GREEN. The two tasks are split only for review granularity.

- [ ] **Step 4: Run test to verify it passes (after Task 9 functions are also appended)**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql profiles_setup.sql profiles_test.sql`
Expected: PASS once Task 9's `sync_push_profiles` is present. If running Task 8 in isolation, expect the push-missing error and proceed to Task 9.

- [ ] **Step 5: Commit**

```bash
git add profiles_setup.sql profiles_test.sql
git commit -m "feat(sync): sync_pull_profiles (no-arg, auto-default, never-error) + sync_pull_profile_locks"
```

---

## Task 9: `sync_push_profiles` + `sync_delete_profile_data` (+ ACLs)

**Files:**
- Modify: `profiles_setup.sql`
- Modify: `profiles_test.sql`

- [ ] **Step 1: Append the failing push/delete tests**

Append to `profiles_test.sql`:

```sql
-- ============ profiles: push (upsert by profile_index; skip out-of-range) + delete_profile_data ============
do $$
declare a uuid := '55555555-5555-aaaa-aaaa-555555555555';
        n int;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- push two profiles; re-push index 1 updates the SAME row (no dup).
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',1,'name','One','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false),
    jsonb_build_object('profile_index',2,'name','Two','avatar_color_hex','#2','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',1,'name','One v2','avatar_color_hex','#1','uses_primary_addons',true,'uses_primary_plugins',false)));
  select count(*) into n from public.profiles where user_id=a;
  assert n = 2, format('re-push must not duplicate; expected 2 profiles, got %s', n);
  assert (select name from public.profiles where user_id=a and profile_index=1) = 'One v2', 're-push must overwrite name';
  assert (select uses_primary_addons from public.profiles where user_id=a and profile_index=1) = true, 're-push must overwrite flag';

  -- out-of-range / malformed indices are skipped (defensive use of p_client_max_profiles).
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',99,'name','TooBig','avatar_color_hex','#9','uses_primary_addons',false,'uses_primary_plugins',false),
    jsonb_build_object('profile_index',0,'name','Zero','avatar_color_hex','#0','uses_primary_addons',false,'uses_primary_plugins',false)));
  assert not exists (select 1 from public.profiles where user_id=a and profile_index in (0,99)),
    'out-of-range profile_index (>max or <1) must be skipped';

  raise notice 'profiles push OK';
end $$;

-- sync_delete_profile_data: wipes the owner's rows for a profile across ALL subsystems; default (1) is guarded.
do $$
declare a uuid := '66666666-6666-aaaa-aaaa-666666666666';
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);
  -- seed data for profile 2 across several subsystems.
  perform public.sync_push_profiles(5, jsonb_build_array(
    jsonb_build_object('profile_index',2,'name','P2','avatar_color_hex','#2','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_push_collections(2, jsonb_build_array(jsonb_build_object('id','c')));
  perform public.sync_push_home_catalog_settings(2, jsonb_build_object('k','v'), 'tv');
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','x','content_type','movie','name','X','poster_shape','POSTER','genres', jsonb_build_array(),'added_at',1)), 2);

  -- delete everything for profile 2.
  perform public.sync_delete_profile_data(2);
  assert not exists (select 1 from public.profiles where user_id=a and profile_index=2), 'profile row must be deleted';
  assert not exists (select 1 from public.collections where user_id=a and profile_id=2), 'collections must be deleted';
  assert not exists (select 1 from public.home_catalog_settings where user_id=a and profile_id=2), 'home_catalog must be deleted';
  assert not exists (select 1 from public.library where user_id=a and profile_id=2), 'library must be deleted';

  -- default profile (1) is server-guarded (matches the client guard) — deleting it is a no-op.
  perform public.sync_push_collections(1, jsonb_build_array(jsonb_build_object('id','keep')));
  perform public.sync_delete_profile_data(1);
  assert exists (select 1 from public.collections where user_id=a and profile_id=1),
    'default profile (1) data must survive delete (server-guarded)';

  raise notice 'sync_delete_profile_data OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run (this test references collections/home_catalog/library, so include their setups):
```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql profiles_test.sql
```
Expected: FAIL — `function public.sync_push_profiles(...) does not exist` (until Step 3).

- [ ] **Step 3: Append the push + delete functions (+ ACLs)**

Append to `profiles_setup.sql`:

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql profiles_test.sql
```
Expected: PASS — `ALL SYNC SQL TESTS PASSED` (profiles schema/pull/push/delete + locks).

- [ ] **Step 5: Commit**

```bash
git add profiles_setup.sql profiles_test.sql
git commit -m "feat(sync): sync_push_profiles + sync_delete_profile_data (default-profile guard, ACLs)"
```

---

## Task 10: `profiles` ACL + NULL-owner + RLS read-own + teardown + idempotency

**Files:**
- Create: `profiles_teardown.sql`
- Modify: `profiles_test.sql`

- [ ] **Step 1: Append the ACL + NULL-owner + RLS + idempotency tests**

Append to `profiles_test.sql`:

```sql
-- ============ profiles: function ACLs ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.sync_push_profiles_for(uuid,int,jsonb)', 'EXECUTE'),
    'push _for must NOT be executable by authenticated';
  assert not has_function_privilege('authenticated', 'public.sync_delete_profile_data_for(uuid,int)', 'EXECUTE'),
    'delete _for must NOT be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_profiles()', 'EXECUTE'),
    'pull profiles wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_pull_profile_locks()', 'EXECUTE'),
    'pull locks wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_push_profiles(int,jsonb)', 'EXECUTE'),
    'push profiles wrapper must be executable by authenticated';
  assert has_function_privilege('authenticated', 'public.sync_delete_profile_data(int)', 'EXECUTE'),
    'delete wrapper must be executable by authenticated';
  raise notice 'profiles ACLs OK';
end $$;

-- RLS read-own (profiles).
do $$
declare a uuid := 'aaaaaaaa-1111-aaaa-aaaa-aaaaaaaaaaaa';
        b uuid := 'bbbbbbbb-1111-aaaa-bbbb-bbbbbbbbbbbb';
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;
  perform public.test_login(a);
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object('profile_index',2,'name','rls_a','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.test_login(b);
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object('profile_index',2,'name','rls_b','avatar_color_hex','#2','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.test_login(a);
end $$;
set local role authenticated;
do $$
begin
  assert (select count(*) from public.profiles where name='rls_b') = 0, 'RLS must hide member B''s profiles from A';
  assert (select count(*) from public.profiles where name='rls_a') >= 1, 'A must see its own profiles';
  raise notice 'profiles RLS read-own OK';
end $$;
reset role;

-- NULL-owner safety (R4). NOTE: sync_pull_profiles() intentionally returns a synthesized default row
-- even for an anon caller (it must NEVER error); it must NOT leak any stored member's data.
do $$
declare before_p int; after_p int; n int; v_idx int;
begin
  perform public.test_logout();
  select count(*) into before_p from public.profiles;
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object('profile_index',2,'name','anon','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_delete_profile_data(2);
  select count(*) into after_p from public.profiles;
  assert after_p = before_p, 'anon push/delete must not change profiles row count';
  assert not exists (select 1 from public.profiles where user_id is null), 'no NULL-user_id profile rows';

  -- anon pull never errors and returns only the synthesized default (no stored member data).
  select count(*) into n from public.sync_pull_profiles();
  assert n = 1, format('anon sync_pull_profiles must return exactly the synth default, got %s', n);
  select profile_index into v_idx from public.sync_pull_profiles();
  assert v_idx = 1, 'anon synth default profile_index must be 1';
  assert not exists (select 1 from public.sync_pull_profiles() where name <> ''), 'anon pull must not leak any stored profile';
  assert (select count(*) from public.sync_pull_profile_locks()) = 0, 'anon profile_locks pull must be empty';
  raise notice 'profiles NULL-owner OK';
end $$;

-- ============ profiles: idempotent re-apply preserves data ============
do $$
declare s uuid := 'ffffffff-1111-aaaa-ffff-ffffffffffff';
begin
  insert into auth.users(id) values (s) on conflict do nothing;
  perform public.test_login(s);
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object('profile_index',3,'name','idemp','avatar_color_hex','#3','uses_primary_addons',false,'uses_primary_plugins',false)));
end $$;

-- re-apply the whole setup mid-test (comment kept off the \i line).
\i profiles_setup.sql

do $$
declare s uuid := 'ffffffff-1111-aaaa-ffff-ffffffffffff';
begin
  perform public.test_login(s);
  assert (select count(*) from public.profiles where user_id=s and profile_index=3 and name='idemp') = 1,
    're-applying setup must PRESERVE existing profiles';
  raise notice 'profiles idempotency OK';
end $$;
```

- [ ] **Step 2: Run test to verify it passes**

Run:
```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql profiles_test.sql
```
Expected: PASS.

- [ ] **Step 3: Write `profiles_teardown.sql`**

Create `profiles_teardown.sql`:

```sql
-- Rollback of profiles_setup.sql. Removes ONLY profiles objects (incl. the cross-subsystem
-- sync_delete_profile_data RPC). Does NOT touch get_sync_owner (shared), auth.users, or any sibling
-- DATA (watch_progress/watched_items/library/collections/home_catalog/profile_settings). Idempotent.

drop function if exists public.sync_pull_profiles();
drop function if exists public.sync_pull_profile_locks();
drop function if exists public.sync_push_profiles(int, jsonb);
drop function if exists public.sync_push_profiles_for(uuid, int, jsonb);
drop function if exists public.sync_delete_profile_data(int);
drop function if exists public.sync_delete_profile_data_for(uuid, int);

drop table if exists public.profile_locks;
drop table if exists public.profiles;

select 'profiles' as obj, to_regclass('public.profiles') as still_exists
union all select 'profile_locks', to_regclass('public.profile_locks');
```

- [ ] **Step 4: Verify teardown drops cleanly, then restore the branch**

```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql profiles_teardown.sql
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql
psql "$SYNC_TEST_DB_URL" -At -c "select 'profiles='||coalesce(to_regclass('public.profiles')::text,'MISSING')||',profile_locks='||coalesce(to_regclass('public.profile_locks')::text,'MISSING')"
```
Expected: teardown sanity `select` shows both gone; re-apply exits 0; final check prints `profiles=profiles,profile_locks=profile_locks`.

- [ ] **Step 5: Commit**

```bash
git add profiles_teardown.sql profiles_test.sql
git commit -m "feat(sync): profiles ACL + NULL-owner + RLS tests, teardown + idempotency"
```

---

## Task 11: `prune_sync_events` retention (R10)

**Files:**
- Create: `sync_maintenance_setup.sql`
- Create: `sync_maintenance_test.sql`

> **R10:** the `*_events` logs (`watch_progress_events`, `watched_items_events`) grow unbounded across ~330 members. `prune_sync_events` deletes event-log rows older than a retention window. It NEVER touches state tables — the snapshot pulls (`sync_pull_watch_progress`, `sync_pull_watched_items`) remain the full-fidelity restore path, so pruning old consumed deltas is safe. Mirrors `prune_telemetry`; admin/cron-only (no app-role grant — the branch has no `kevbox_admin`).

- [ ] **Step 1: Write the failing prune test**

Create `sync_maintenance_test.sql`:

```sql
-- ============ prune_sync_events: retention (R10) ============
do $$
declare a uuid := '11111111-9999-9999-9999-111111111111';
        n_old int; n_new int; v_msg text;
begin
  insert into auth.users(id) values (a) on conflict do nothing;
  perform public.test_login(a);

  -- create one watch_progress event + one watched_items event (recent).
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','wp','content_type','movie','video_id','v','position',1,'duration',10,
    'last_watched',1,'progress_key','wp:1')), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wi','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);

  -- backdate the watch_progress event well past the window (the test connection is table owner).
  update public.watch_progress_events set created_at = now() - interval '400 days' where user_id=a;

  -- prune events older than 180 days: the backdated wp event goes; the recent wi event stays.
  select public.prune_sync_events(180) into v_msg;
  select count(*) into n_old from public.watch_progress_events where user_id=a;
  select count(*) into n_new from public.watched_items_events  where user_id=a;
  assert n_old = 0, format('old watch_progress event must be pruned, got %s', n_old);
  assert n_new = 1, format('recent watched_items event must survive, got %s', n_new);
  assert v_msg like 'pruned %', format('prune must return a summary string; got %s', v_msg);

  raise notice 'prune_sync_events OK';
end $$;

-- ============ prune_sync_events: ACLs (admin/cron-only — NOT member-facing) ============
do $$
begin
  assert not has_function_privilege('authenticated', 'public.prune_sync_events(int)', 'EXECUTE'),
    'prune must NOT be executable by authenticated';
  assert not has_function_privilege('anon', 'public.prune_sync_events(int)', 'EXECUTE'),
    'prune must NOT be executable by anon';
  raise notice 'prune_sync_events ACLs OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql sync_maintenance_test.sql`
Expected: FAIL with `function public.prune_sync_events(integer) does not exist`.

- [ ] **Step 3: Write `sync_maintenance_setup.sql` (prune only for now)**

Create `sync_maintenance_setup.sql`:

```sql
-- KevBox TV — cloud-restore maintenance/observability. Run ONCE AFTER the Plan-1/2/3 data setups
-- (references watch_progress/watched_items/library/collections/home_catalog/profiles). Idempotent.
-- Spec §7 (get_sync_overview), §10/R10 (prune_sync_events). No secrets.

-- 1. R10 retention. Deletes ONLY append-only event-log rows older than p_event_days. NEVER touches
--    state tables (snapshot pulls remain the full-fidelity restore). Mirrors prune_telemetry.
create or replace function public.prune_sync_events(p_event_days int default 180)
  returns text language plpgsql security definer set search_path = '' as $$
declare v_wp bigint; v_wi bigint;
begin
  delete from public.watch_progress_events where created_at < now() - make_interval(days => p_event_days);
  get diagnostics v_wp = row_count;
  delete from public.watched_items_events  where created_at < now() - make_interval(days => p_event_days);
  get diagnostics v_wi = row_count;
  return format('pruned %s watch_progress_events, %s watched_items_events', v_wp, v_wi);
end $$;

-- Admin/cron-only: revoke from all app roles. Run via pg_cron (as the job owner) or manual admin psql.
-- The branch has no kevbox_admin, so we do NOT grant to it (unlike prune_telemetry); the definer/owner
-- and any superuser/cron context can always execute.
revoke all on function public.prune_sync_events(int) from public, anon, authenticated;
-- Suggested schedule (run manually on prod once pg_cron is available):
--   select cron.schedule('prune_sync_events_daily', '30 4 * * *', $$ select public.prune_sync_events(180) $$);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql sync_maintenance_setup.sql sync_maintenance_test.sql`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sync_maintenance_setup.sql sync_maintenance_test.sql
git commit -m "feat(sync): prune_sync_events retention (R10, admin/cron-only)"
```

---

## Task 12: `get_sync_overview` (owner-scoped row counts; §7/§8 probe)

**Files:**
- Modify: `sync_maintenance_setup.sql`
- Modify: `sync_maintenance_test.sql`

> **§7/§8:** member-facing (Account screen Sync panel, `AccountViewModel.kt:413`, `decodeAs<SyncOverviewResponse>`, `runCatching` fail-soft). Returns a single jsonb object of owner-scoped per-profile counts. Doubles as a lightweight detection probe. `addons`/`plugins` are intentionally empty (`member_addon` owns addons; plugins are global).

- [ ] **Step 1: Append the failing overview test**

Append to `sync_maintenance_test.sql`:

```sql
-- ============ get_sync_overview: owner-scoped per-profile counts (§7) ============
do $$
declare a uuid := '22222222-9999-9999-9999-222222222222';
        b uuid := '33333333-9999-9999-9999-333333333333';
        ov jsonb;
begin
  insert into auth.users(id) values (a),(b) on conflict do nothing;

  perform public.test_login(a);
  perform public.sync_push_library(jsonb_build_array(
    jsonb_build_object('content_id','l1','content_type','movie','name','L1','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1),
    jsonb_build_object('content_id','l2','content_type','movie','name','L2','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','wi','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object(
    'profile_index',1,'name','Main','avatar_color_hex','#abc','uses_primary_addons',false,'uses_primary_plugins',false)));

  -- member B has different data (must not bleed into A's overview).
  perform public.test_login(b);
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','bl','content_type','movie','name','BL','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);

  perform public.test_login(a);
  select public.get_sync_overview() into ov;

  -- shape: all 6 SyncOverviewResponse keys present (maps default-safe, but assert structure).
  assert ov ? 'library_items' and ov ? 'watch_progress' and ov ? 'watched_items'
     and ov ? 'profiles' and ov ? 'addons' and ov ? 'plugins',
    format('overview must carry all SyncOverviewResponse keys; got %s', ov::text);
  -- A's profile-1 counts (R1: only A's data).
  assert (ov #>> '{library_items,1}')::int = 2, format('library_items[1] must be 2; got %s', ov #>> '{library_items,1}');
  assert (ov #>> '{watched_items,1}')::int = 1, format('watched_items[1] must be 1; got %s', ov #>> '{watched_items,1}');
  assert ov #>> '{profiles,1,name}' = 'Main', format('profiles[1].name must be Main; got %s', ov #>> '{profiles,1,name}');
  assert (ov #>> '{library_items,1}')::int <> 3, 'must not include member B''s library row';
  assert jsonb_typeof(ov->'addons') = 'object' and jsonb_typeof(ov->'plugins') = 'object', 'addons/plugins must be (empty) objects';

  raise notice 'get_sync_overview OK';
end $$;

-- ============ get_sync_overview: ACL + anon safety ============
do $$
declare ov jsonb;
begin
  assert has_function_privilege('authenticated', 'public.get_sync_overview()', 'EXECUTE'),
    'get_sync_overview must be executable by authenticated';
  -- anon: never errors; all maps empty (no data leak).
  perform public.test_logout();
  select public.get_sync_overview() into ov;
  assert ov ? 'library_items' and jsonb_typeof(ov->'library_items') = 'object', 'anon overview must be a valid object';
  assert ov #>> '{library_items,1}' is null, 'anon overview must contain no data';
  raise notice 'get_sync_overview anon OK';
end $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql sync_maintenance_setup.sql sync_maintenance_test.sql`
Expected: FAIL with `function public.get_sync_overview() does not exist`.

- [ ] **Step 3: Append `get_sync_overview` to `sync_maintenance_setup.sql`**

Append to `sync_maintenance_setup.sql`:

```sql
-- 2. get_sync_overview (§7). Owner-scoped per-profile row counts as a single jsonb object decoded by
--    SyncOverviewResponse (addons/plugins/library_items/watch_progress/watched_items: {profileId->count};
--    profiles: {profileIndex->{name,color}}). addons/plugins are empty ({}) — member_addon owns addons,
--    plugins are global. Member-facing + doubles as a §8 detection probe. NULL owner => all-empty object.
--    `language sql` => all referenced tables must exist at CREATE time (apply after Plan-1/2/3 data setups).
create or replace function public.get_sync_overview()
  returns jsonb language sql security definer set search_path = '' as $$
  with o as (select nullif(public.get_sync_owner(),'')::uuid as uid)
  select jsonb_build_object(
    'addons',  '{}'::jsonb,
    'plugins', '{}'::jsonb,
    'library_items', coalesce((
      select jsonb_object_agg(profile_id::text, n) from (
        select l.profile_id, count(*) n from public.library l, o where l.user_id = o.uid group by l.profile_id) s), '{}'::jsonb),
    'watch_progress', coalesce((
      select jsonb_object_agg(profile_id::text, n) from (
        select w.profile_id, count(*) n from public.watch_progress w, o where w.user_id = o.uid group by w.profile_id) s), '{}'::jsonb),
    'watched_items', coalesce((
      select jsonb_object_agg(profile_id::text, n) from (
        select wi.profile_id, count(*) n from public.watched_items wi, o where wi.user_id = o.uid group by wi.profile_id) s), '{}'::jsonb),
    'profiles', coalesce((
      select jsonb_object_agg(profile_index::text, jsonb_build_object('name', name, 'color', avatar_color_hex)) from (
        select p.profile_index, p.name, p.avatar_color_hex from public.profiles p, o where p.user_id = o.uid) s), '{}'::jsonb)
  )
$$;
revoke all     on function public.get_sync_overview() from public, anon;
grant  execute on function public.get_sync_overview() to authenticated;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql sync_maintenance_setup.sql sync_maintenance_test.sql`
Expected: PASS.

- [ ] **Step 5: Write `sync_maintenance_teardown.sql` and commit**

Create `sync_maintenance_teardown.sql`:

```sql
-- Rollback of sync_maintenance_setup.sql. Removes ONLY the maintenance/observability functions.
-- Does NOT touch any data tables or get_sync_owner. Idempotent.

drop function if exists public.prune_sync_events(int);
drop function if exists public.get_sync_overview();

select 'prune_sync_events' as obj, to_regprocedure('public.prune_sync_events(int)') as still_exists
union all select 'get_sync_overview', to_regprocedure('public.get_sync_overview()');
```

Verify teardown + restore:
```bash
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql sync_maintenance_setup.sql sync_maintenance_teardown.sql
./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql sync_maintenance_setup.sql
psql "$SYNC_TEST_DB_URL" -At -c "select 'overview='||coalesce(to_regprocedure('public.get_sync_overview()')::text,'MISSING')"
```
Expected: teardown clean; re-apply exits 0; final check prints `overview=get_sync_overview()`.

```bash
git add sync_maintenance_setup.sql sync_maintenance_teardown.sql sync_maintenance_test.sql
git commit -m "feat(sync): get_sync_overview owner-scoped counts (§7) + maintenance teardown"
```

---

## Task 13: Post-deploy RPC-probe script (§9 detection)

**Files:**
- Create: `probe_sync_rpcs.sql`

> **§9:** restore failures are silent (logcat-only). The probe calls every deployed RPC as a real test member and asserts none return `42883` (undefined function) / decode-shape errors — the detection signal before fleet-wide go-live. It runs inside a rolled-back transaction (safe on the branch AND live — nothing commits). On the branch it uses `public.test_login`; for live, see the runbook (set `request.jwt.claims` to a real test member and skip the temp-user insert).

- [ ] **Step 1: Write `probe_sync_rpcs.sql`**

Create `probe_sync_rpcs.sql`:

```sql
-- KevBox TV cloud-restore — post-deploy RPC probe (spec §9). Calls every deployed sync RPC as a test
-- member and asserts none raise (a missing/renamed RPC raises 42883; a wrong return shape raises on the
-- aggregate). Run inside ONE rolled-back transaction so nothing commits — safe on branch OR live prod.
--   Branch:  psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -f sync_test_helpers.sql -f probe_sync_rpcs.sql
--   Live:    replace the test_login(...) seed with a real member's JWT claims (runbook) and DROP the
--            `insert into auth.users` line (the member already exists).
begin;
do $$
declare m uuid := 'deadbeef-9999-4444-9999-deadbeefbeef';
        sink_b bigint; sink_j jsonb; sink_n int;
begin
  insert into auth.users(id) values (m) on conflict do nothing;   -- branch only; remove for live
  perform public.test_login(m);

  -- get_sync_owner
  perform public.get_sync_owner();

  -- watch_progress (5)
  perform public.sync_push_watch_progress(jsonb_build_array(jsonb_build_object(
    'content_id','p','content_type','movie','video_id','v','position',1,'duration',2,'last_watched',1,'progress_key','p:1')), 1);
  perform count(*) from public.sync_pull_watch_progress(1, null, null);
  select public.sync_get_watch_progress_delta_cursor(1) into sink_b;
  perform count(*) from public.sync_pull_watch_progress_delta(1, 0, 10);
  perform public.sync_delete_watch_progress(jsonb_build_array('p:1'), 1);

  -- watched_items (5)
  perform public.sync_push_watched_items(jsonb_build_array(jsonb_build_object(
    'content_id','w','content_type','movie','title','W','season',null,'episode',null,'watched_at',1)), 1);
  perform count(*) from public.sync_pull_watched_items(1, 1, 10);
  select public.sync_get_watched_items_delta_cursor(1) into sink_b;
  perform count(*) from public.sync_pull_watched_items_delta(1, 0, 10);
  perform public.sync_delete_watched_items(1, jsonb_build_array(jsonb_build_object('content_id','w')));

  -- library (2)
  perform public.sync_push_library(jsonb_build_array(jsonb_build_object(
    'content_id','l','content_type','movie','name','L','poster_shape','POSTER','genres',jsonb_build_array(),'added_at',1)), 1);
  perform count(*) from public.sync_pull_library(1, 10, 0);

  -- collections (2)
  perform public.sync_push_collections(1, jsonb_build_array());
  perform count(*) from public.sync_pull_collections(1);

  -- home_catalog_settings (2)  — probe all three pulled platforms
  perform public.sync_push_home_catalog_settings(1, jsonb_build_object(), 'home_catalog_shared');
  perform count(*) from public.sync_pull_home_catalog_settings(1, 'home_catalog_shared');
  perform count(*) from public.sync_pull_home_catalog_settings(1, 'tv');
  perform count(*) from public.sync_pull_home_catalog_settings(1, 'mobile');

  -- profile_settings_blob (2)
  perform public.sync_push_profile_settings_blob(1, jsonb_build_object('version',1,'features',jsonb_build_object()), 'tv');
  perform count(*) from public.sync_pull_profile_settings_blob(1, 'tv');

  -- profiles (4)
  select count(*) into sink_n from public.sync_pull_profiles();
  assert sink_n >= 1, 'sync_pull_profiles must return >=1 row (the un-guarded startup pull)';
  perform count(*) from public.sync_pull_profile_locks();
  perform public.sync_push_profiles(5, jsonb_build_array(jsonb_build_object(
    'profile_index',2,'name','probe','avatar_color_hex','#1','uses_primary_addons',false,'uses_primary_plugins',false)));
  perform public.sync_delete_profile_data(2);

  -- get_sync_overview (1)
  select public.get_sync_overview() into sink_j;

  raise notice 'PROBE OK — all sync RPCs resolved and returned without 42883/shape errors';
end $$;
rollback;
```

- [ ] **Step 2: Run the probe against the fully-deployed branch**

Ensure all subsystems are deployed on the branch first (they are, from Tasks 1–12 + Plans 1–2). Run:
```bash
source .supabase_db.env
psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -f sync_test_helpers.sql -f probe_sync_rpcs.sql
```
Expected: prints `PROBE OK — all sync RPCs resolved and returned without 42883/shape errors` and `ROLLBACK`. If any RPC is missing/misnamed, psql aborts with the failing function — fix the corresponding setup.

- [ ] **Step 3: Commit**

```bash
git add probe_sync_rpcs.sql
git commit -m "feat(sync): post-deploy RPC probe across all 23 cloud-restore RPCs (§9 detection)"
```

---

## Task 14: Deployment runbook (§10) + go-live gate

**Files:**
- Create: `CLOUD-RESTORE-RUNBOOK.md`

- [ ] **Step 1: Write `CLOUD-RESTORE-RUNBOOK.md`**

Create `CLOUD-RESTORE-RUNBOOK.md` at the repo root:

```markdown
# KevBox TV Cloud-Restore — Deployment & Verification Runbook

Restores a member's watch progress / watched history / library / collections / settings / profiles
from Supabase on reinstall or fresh-TV login, with **zero Android client changes**. This runbook
covers deploying the server schema (Plans 1–3) to the live KevBox Supabase project.

## Objects deployed (all bare upstream names; disjoint from `member_*`/`kevbox_*`)

- Foundation: `get_sync_owner()`
- Plan 1: `watch_progress`, `watch_progress_events` + 5 RPCs
- Plan 2: `watched_items`, `watched_items_events` + 5 RPCs; `library` + 2 RPCs
- Plan 3: `collections` + 2 RPCs; `home_catalog_settings` + 2 RPCs; `profile_settings_blob` + 2 RPCs;
  `profiles`, `profile_locks` + 4 RPCs; `prune_sync_events`; `get_sync_overview`

## Deploy order (MANDATORY — later objects reference earlier ones)

Apply `*_setup.sql` in this order against the **live** project (e.g. `psql "$LIVE_DB_URL" -f <file>`):

1. `get_sync_owner_setup.sql`
2. `watch_progress_setup.sql`
3. `watched_items_setup.sql`
4. `library_setup.sql`
5. `collections_setup.sql`
6. `home_catalog_settings_setup.sql`
7. `profile_settings_blob_setup.sql`
8. `profiles_setup.sql`  ← `sync_delete_profile_data` references the Plan-1/2/3 data tables
9. `sync_maintenance_setup.sql`  ← `get_sync_overview` is `language sql`; all referenced tables must exist

> All setups are idempotent (`create … if not exists` / `create or replace`). Re-running is safe.

## Canary path (mitigates fleet-wide blast radius)

Applying the setups flips restore **on** fleet-wide at each member's next `FullAccount` emission; there
is **no** per-member flag. Before fleet-wide enable:

1. Deploy to the **branch DB** and run the full SQL suite green (see Verification).
2. Build a **single canary** full-flavor APK pointed at the live project, sideload to ONE test TV,
   and run T-E2E (play → clear data → re-login → confirm restore).
3. Only then apply the setups to the live project for the fleet.

## Verification (after any deploy — branch or live)

1. **RPC probe (detection signal):**
   - Branch: `psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -f sync_test_helpers.sql -f probe_sync_rpcs.sql`
   - Live: edit `probe_sync_rpcs.sql` — remove the `insert into auth.users` line and replace
     `test_login(m)` with a real test member's claims, e.g.
     `select set_config('request.jwt.claims', '{"sub":"<REAL_MEMBER_UUID>"}', true);` — then run it.
     It is wrapped in `begin … rollback`, so nothing commits.
   Expect `PROBE OK …`.
2. **Contract drift check (per §11):** on every upstream merge, diff `core/sync/*SyncService.kt` +
   `SupabaseModels.kt` against the deployed `*_setup.sql` and re-run the probe. Add this to `UPSTREAM-SYNC.md`.
3. **Live regression (T-REG):** confirm `get_access_verdict` / `claim_device` / `member_addon` apply /
   `record_heartbeat` still behave, and that `get_sync_owner` did not pre-exist before deploy.

## Retention (R10)

`prune_sync_events(p_event_days int default 180)` deletes only event-log rows older than the window
(never state). It is admin/cron-only (revoked from all app roles). Schedule on the live project once
`pg_cron` is available:
`select cron.schedule('prune_sync_events_daily', '30 4 * * *', $$ select public.prune_sync_events(180) $$);`
Or run manually: `psql "$LIVE_DB_URL" -c "select public.prune_sync_events(180)"`.

## Rollback (DATA-DESTRUCTIVE — the only rollback)

Run the matching `*_teardown.sql` in REVERSE dependency order:
`sync_maintenance_teardown.sql`, `profiles_teardown.sql`, `profile_settings_blob_teardown.sql`,
`home_catalog_settings_teardown.sql`, `collections_teardown.sql`, `library_teardown.sql`,
`watched_items_teardown.sql`, `watch_progress_teardown.sql` (leave `get_sync_owner` unless fully
reverting). **Teardown DROPS the stored rows** — any data written during the canary/live window is lost.
Dropping the RPCs reverts every member to local-only at next start, no client update needed.

> **Panic note:** because restore failures are silent and fail-soft per-subsystem (except the un-guarded
> `sync_pull_profiles`, which must never error — it auto-synthesizes a default), dropping a single
> subsystem's RPCs cleanly disables just that subsystem.
```

- [ ] **Step 2: Record the go-live gate (no commit needed beyond the doc)**

Per spec §12, the go-live gate is now SATISFIED for the gating artifacts: the §9 detection probe (`probe_sync_rpcs.sql`), the §8 canary path (runbook), and the §10 runbook (`CLOUD-RESTORE-RUNBOOK.md`) all exist. Fleet-wide live deploy may proceed AFTER: branch suite green (Task 15) + a single canary build's T-E2E pass. The only rollback remains data-destructive teardown.

- [ ] **Step 3: Commit**

```bash
git add CLOUD-RESTORE-RUNBOOK.md
git commit -m "docs(sync): cloud-restore deployment runbook (deploy order, canary, rollback, retention)"
```

---

## Task 15: Full cross-subsystem suite green (Plans 1+2+3) + contract cross-check + probe

**Files:** none (verification only).

- [ ] **Step 1: Run the complete cross-subsystem suite from scratch**

```bash
source .supabase_db.env
./run_sync_tests.sh \
  get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
  collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql \
  sync_maintenance_setup.sql \
  watch_progress_test.sql watched_items_test.sql library_test.sql \
  collections_test.sql home_catalog_settings_test.sql profile_settings_blob_test.sql profiles_test.sql \
  sync_maintenance_test.sql
```
Expected: PASS — `ALL SYNC SQL TESTS PASSED`, covering all Plan 1 + Plan 2 + Plan 3 assert blocks (no regression across the shared `get_sync_owner` foundation).

- [ ] **Step 2: Run the RPC probe**

```bash
psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -f sync_test_helpers.sql -f probe_sync_rpcs.sql
```
Expected: `PROBE OK — all sync RPCs resolved …`.

- [ ] **Step 3: Confirm the RPC contract matches the client call sites (no code change)**

Diff every Plan-3 function name + **argument-name set** against the client (PostgREST binds by name):
- `sync_push_collections(p_profile_id int, p_collections_json jsonb)` ↔ `CollectionSyncService.kt`
- `sync_pull_collections(p_profile_id int)` ↔ `CollectionSyncService.kt`
- `sync_push_home_catalog_settings(p_profile_id int, p_settings_json jsonb, p_platform text)` ↔ `HomeCatalogSettingsSyncService.kt`
- `sync_pull_home_catalog_settings(p_profile_id int, p_platform text)` ↔ `HomeCatalogSettingsSyncService.kt` (×3 platforms)
- `sync_push_profile_settings_blob(p_profile_id int, p_settings_json jsonb, p_platform text)` ↔ `ProfileSettingsSyncService.kt`
- `sync_pull_profile_settings_blob(p_profile_id int, p_platform text)` ↔ `ProfileSettingsSyncService.kt`
- `sync_pull_profiles()` / `sync_pull_profile_locks()` (NO ARGS) ↔ `ProfileSyncService.kt`
- `sync_push_profiles(p_client_max_profiles int, p_profiles jsonb)` ↔ `ProfileSyncService.kt`
- `sync_delete_profile_data(p_profile_id int)` ↔ `ProfileSyncService.kt`
- `get_sync_overview()` ↔ `AccountViewModel.kt`

Confirm decode targets: collections/home-catalog/profile-settings pulls → `firstOrNull()` over the blob model; profiles → `decodeList<SupabaseProfile>` (non-null `profile_index`); locks → `decodeList<SupabaseProfileLockState>`; overview → `decodeAs<SyncOverviewResponse>` (single object). The Task 1/3/5/8/12 wire-shape asserts already pin the exact emitted key sets.

- [ ] **Step 4: Record final go-live state**

All three go-live artifacts (probe, canary path, runbook) plus R10 retention now exist; the whole cloud-restore effort is branch-green. Per §12, fleet-wide live apply still requires the canary T-E2E pass first. No commit — checklist gate.

---

## Self-Review (author checklist — completed at write time)

**1. Spec coverage (Plan 3 scope = §5.4 + §5.5 + §7 + §8 + §9 + §10 + R10):**
- §5.4 collections (table + 2 RPCs) → Tasks 1–2. ✓ · home_catalog_settings (table + 2 RPCs, multi-platform, empty-set) → Tasks 3–4. ✓ · profile_settings_blob (table + 2 RPCs, verbatim features) → Tasks 5–6. ✓
- §5.5 profiles + profile_locks (2 tables + 4 RPCs; no-arg pulls; auto-default; never-error; profile_index key; non-FK profile_id) → Tasks 7–10. ✓
- §7 `get_sync_overview` (owner-scoped counts; addons/plugins empty) → Task 12. ✓
- §8 canary path + blast-radius caveat → Task 14 runbook. ✓
- §9 detection probe (`probe_sync_rpcs.sql`, all RPCs) + rollback note → Tasks 13, 14. ✓
- §10 deliverables (per-subsystem setups/teardowns, `prune_sync_events`, test scripts, probe, runbook) → Tasks 1–14. ✓
- R10 `prune_sync_events` (events-only, never state; admin/cron-only) → Task 11. ✓
- R1 owner predicate on every read + scoped write/delete → all RPC tasks (+ T-ISO in 1/3/5/8/12). ✓
- R3 PK upsert targets (collections (user,profile); blobs (user,profile,platform); profiles (user,profile_index)) → Tasks 1/3/5/7. ✓
- R4 NULL-owner no-op → Tasks 2/4/6/9/10 (+ profiles synth-default never-error variant). ✓
- R7 exact wire shape (blobs jsonb verbatim; profiles 11-key; non-null profile_index; overview object) → Tasks 1/3/5/8/12. ✓
- R9 idempotent setup / scoped teardown → Tasks 2/4/6/10/12. ✓
- Function ACLs (inner `_for` revoked, wrappers granted, asserted) → all push/delete tasks + the ACL asserts in 2/4/6/10/11/12. ✓
- T-PROFILE (un-guarded pull resolves for brand-new account) → Task 8. ✓

**2. Placeholder scan:** No TBD/TODO; every code step contains complete SQL or the complete doc. ✓

**3. Type consistency:** wrappers — collections `(int,jsonb)`; home_catalog/profile_settings push `(int,jsonb,text)` / pull `(int,text)`; profiles pulls `()`; push `(int,jsonb)`; delete `(int)`; prune `(int)`; overview `()`. Inner fns add a leading `p_owner uuid`. Teardowns drop the exact signatures created; ACL revoke/grant signatures match. `collections_json`/`settings_json` are `jsonb`; `profile_index` is non-null int; the data-table `profile_id` is a plain int (no FK). The synth-default `sync_pull_profiles` row carries a non-null `profile_index=1`. ✓

**4. Cross-subsystem coupling note:** `sync_delete_profile_data` (Task 9) and `get_sync_overview` (Task 12) reference Plan-1/2 tables. `sync_delete_profile_data` is `language plpgsql` (late-bound — safe to CREATE before those tables exist), but `get_sync_overview` is `language sql` (validated at CREATE — `sync_maintenance_setup.sql` MUST be applied after all data setups). The deploy order (runbook) and the test run-commands enforce this. ✓

**5. Pre-execution verification (REQUIRED before TDD execution):** Run a multi-agent workflow that assembles this plan's SQL, RUNS it on the real PG17 branch (rolled-back), and audits contract/security/spec. Fold all actionable findings before executing task-by-task. (Mirrors the Plan 1 & Plan 2 process.)

---

## Execution Handoff

Plan complete and saved. Prerequisite: the Plan-1/2 branch DB + harness (already in place; branch has get_sync_owner + watch_progress + watched_items + library deployed). Two execution options:

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review between, fast iteration.
2. **Inline Execution** — executing-plans, batch with checkpoints.
