# KevBox TV Cloud-Restore — Deployment & Verification Runbook

Restores a member's watch progress / watched history / library / collections / settings / profiles
from Supabase on reinstall or fresh-TV login, with **zero Android client changes**. This runbook
covers deploying the server schema (Plans 1–3) to the live KevBox Supabase project.

## Objects deployed (all bare upstream names; disjoint from `member_*`/`kevbox_*`)

- Foundation: `get_sync_owner()` (**canary-gated**, closed by default) + `sync_canary_members` allowlist table
- Plan 1: `watch_progress`, `watch_progress_events` + 5 RPCs
- Plan 2: `watched_items`, `watched_items_events` + 5 RPCs; `library` + 2 RPCs
- Plan 3: `collections` + 2 RPCs; `home_catalog_settings` + 2 RPCs; `profile_settings_blob` + 2 RPCs;
  `profiles`, `profile_locks` + 4 RPCs; `prune_sync_events`; `get_sync_overview`

## Deploy order (MANDATORY — later objects reference earlier ones)

Apply each `*_setup.sql` (they live in `sql/sync/`) in this order against the **live** project (e.g. `psql "$LIVE_DB_URL" -f sql/sync/<file>`):

1. `sql/sync/get_sync_owner_setup.sql`  ← also creates `sync_canary_members`; the gate is **closed by default** (empty allowlist ⇒ no member syncs ⇒ zero blast radius)
2. `sql/sync/watch_progress_setup.sql`
3. `sql/sync/watched_items_setup.sql`
4. `sql/sync/library_setup.sql`
5. `sql/sync/collections_setup.sql`
6. `sql/sync/home_catalog_settings_setup.sql`
7. `sql/sync/profile_settings_blob_setup.sql`
8. `sql/sync/profiles_setup.sql`  ← `sync_delete_profile_data` references the Plan-1/2/3 data tables
9. `sql/sync/sync_maintenance_setup.sql`  ← `get_sync_overview` is `language sql`; all referenced tables must exist

> All setups are idempotent (`create … if not exists` / `create or replace`). Re-running is safe.

## Canary path (single-member rollout via the allowlist gate)

`get_sync_owner()` is **closed by default**: it returns a member's real owner id **only** if that
member's `auth.uid()` is in `public.sync_canary_members`, else `NULL`. Every sync RPC derives its owner
from `nullif(get_sync_owner(),'')::uuid`, so a `NULL` owner makes every pull empty, every push a no-op,
and `sync_pull_profiles()` **empty** (no synth default → the client skips `replaceAllProfiles` → local
profiles preserved). A non-allowlisted member therefore behaves **exactly like today** (RPCs effectively
off). The allowlist is `NULL`-returning, never `''` — the client's `getEffectiveUserId` does
`decodeAs<String>()` into a non-nullable String, so `NULL` throws there and falls back identically to
today's RPC-absent path; `''` would decode to a bogus non-null owner.

`sync_canary_members` has RLS on and **no grants** to `anon`/`authenticated` — a member cannot read it or
self-allowlist. Only an operator with DB access (or the `SECURITY DEFINER` resolver itself) touches it.

**Procedure:**

1. **Apply all `*_setup.sql` to live prod in the deploy order above.** With the allowlist empty this has
   **zero blast radius** — nobody syncs, every member is unchanged at next app start.
2. **Allowlist only the canary TV's member** (the operator's own Supabase auth user id — find it in
   `auth.users` or the JWT `sub`):
   ```
   insert into public.sync_canary_members(user_id) values ('<YOUR_AUTH_UID>') on conflict do nothing;
   ```
3. **On the canary TV:** play something → clear app data → re-login → confirm continue-watching, history,
   library, collections, settings, and profiles all restore. **Only the allowlisted member syncs**; the
   rest of the fleet is untouched.
4. **PANIC / ABORT** (instantly inert, no client action):
   ```
   truncate public.sync_canary_members;
   ```
   Only the canary member was ever affected, and the `sync_pull_profiles` empty-when-none fix means even
   they are not wiped. (You can also `delete from public.sync_canary_members where user_id = '<UID>'`.)
5. **FLEET-WIDE ENABLE** — only after the canary T-E2E passes. Either:
   - **Cleanest:** replace the resolver with the ungated form and reload:
     ```
     create or replace function public.get_sync_owner() returns text
       language sql security definer set search_path = '' as $$ select auth.uid()::text $$;
     ```
     (then `sync_canary_members` becomes dormant — it can be left in place or dropped), **or**
   - insert every member's `user_id` into `sync_canary_members` (keeps the gate as a live kill-switch:
     `truncate` to disable sync fleet-wide again).

## Verification (after any deploy — branch or live)

1. **Correctness suite (branch only — the strongest signal):** run the full Plan 1+2+3 ASSERT suite via
   the runner (it wraps each `*_test.sql` in its own `begin … rollback`, so fixtures never persist).
   The files live in `sql/sync/`; pass bare names — the runner resolves each under `sql/sync/`:
   ```
   ./run_sync_tests.sh \
     get_sync_owner_setup.sql watch_progress_setup.sql watched_items_setup.sql library_setup.sql \
     collections_setup.sql home_catalog_settings_setup.sql profile_settings_blob_setup.sql profiles_setup.sql \
     sync_maintenance_setup.sql \
     get_sync_owner_test.sql sync_canary_test.sql \
     watch_progress_test.sql watched_items_test.sql library_test.sql \
     collections_test.sql home_catalog_settings_test.sql profile_settings_blob_test.sql profiles_test.sql \
     sync_maintenance_test.sql
   ```
   Expect `ALL SYNC SQL TESTS PASSED`. `sync_canary_test.sql` proves the gate: a logged-in but
   non-allowlisted member resolves `owner=NULL`, gets empty pulls / no-op pushes / **empty
   `sync_pull_profiles`** (no wipe), while an allowlisted member round-trips. (`test_login` allowlists
   its member, so the rest of the suite exercises the gate-open path.) Do NOT run the `*_test.sql` files via raw `psql -f` in autocommit —
   they rely on the runner's per-file transaction rollback (their `test_login` GUC is transaction-local and
   their first push would otherwise commit seed rows to the branch).
2. **RPC probe (detection signal — branch OR live):**
   - Branch: `psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -f sql/sync/sync_test_helpers.sql -f sql/sync/probe_sync_rpcs.sql`
   - Live: edit `sql/sync/probe_sync_rpcs.sql` — remove the `insert into auth.users` line and replace
     `test_login(m)` with a real test member's claims, e.g.
     `select set_config('request.jwt.claims', '{"sub":"<REAL_MEMBER_UUID>"}', true);` — then run it.
     It is wrapped in `begin … rollback`, so nothing commits.
   Expect `PROBE OK …`.
3. **Contract drift check (per §11):** on every upstream merge, diff `core/sync/*SyncService.kt` +
   `SupabaseModels.kt` against the deployed `sql/sync/*_setup.sql` and re-run the probe. Add this to `UPSTREAM-SYNC.md`.
4. **Live regression (T-REG):** confirm `get_access_verdict` / `claim_device` / `member_addon` apply /
   `record_heartbeat` still behave, and that `get_sync_owner` did not pre-exist before deploy.

## Retention (R10)

`prune_sync_events(p_event_days int default 180)` deletes only event-log rows older than the window
(never state). It is admin/cron-only (revoked from all app roles). Schedule on the live project once
`pg_cron` is available:
`select cron.schedule('prune_sync_events_daily', '30 4 * * *', $$ select public.prune_sync_events(180) $$);`
Or run manually: `psql "$LIVE_DB_URL" -c "select public.prune_sync_events(180)"`.

## Rollback (DATA-DESTRUCTIVE — the only rollback)

Run the matching `sql/sync/*_teardown.sql` in REVERSE dependency order:
`sql/sync/sync_maintenance_teardown.sql`, `sql/sync/profiles_teardown.sql`, `sql/sync/profile_settings_blob_teardown.sql`,
`sql/sync/home_catalog_settings_teardown.sql`, `sql/sync/collections_teardown.sql`, `sql/sync/library_teardown.sql`,
`sql/sync/watched_items_teardown.sql`, `sql/sync/watch_progress_teardown.sql` (leave `get_sync_owner` unless fully
reverting; `sql/sync/get_sync_owner_teardown.sql` drops `sync_canary_members` **together with** the resolver, and
only when no `sync_*` dependents remain — dropping the table while the resolver survived would make it
raise `42P01`). **Teardown DROPS the stored rows** — any data written during the canary/live window is lost.
Dropping the RPCs reverts every member to local-only at next start, no client update needed.

> **Reach for `truncate public.sync_canary_members` first** — it disables sync instantly with no DDL and
> no data loss, and (unlike teardown) cannot break the resolver. Teardown is the heavier, data-destructive
> revert; the allowlist truncate is the fast kill-switch.

> **Panic note:** because restore failures are silent and fail-soft per-subsystem (except the un-guarded
> `sync_pull_profiles`, which must never error — it returns empty when the member has no cloud profiles),
> dropping a single subsystem's RPCs cleanly disables just that subsystem.

## Known gaps (out of cloud-restore scope — not drift)

- **Profile-PIN write RPCs are unimplemented server-side.** `ProfileSyncService` calls
  `set_profile_pin` / `verify_profile_pin` / `clear_profile_pin`, which do not exist in any `*_setup.sql`
  and are intentionally outside this effort's contract — cloud-restore delivers only the read-side
  `sync_pull_profile_locks` (so it can RESTORE pin state, not write it). The client fail-soft catches their
  absence. Consequence: until those RPCs are built, `profile_locks` is never populated and
  `sync_pull_profile_locks` always restores empty (no PIN). This is expected — do NOT treat the missing
  RPCs as a probe/contract failure (the probe correctly does not call them). Implementing the PIN write
  path is a separate workstream.
