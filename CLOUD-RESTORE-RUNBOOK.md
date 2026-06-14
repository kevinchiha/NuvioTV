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
