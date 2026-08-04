# `sql/` — Supabase server-side SQL

Server schema for the KevBox cloud features. These are **not** sequential migrations — they are
idempotent, per-object setup/teardown/test triads (`create … if not exists` / `create or replace`),
so they are grouped by object name, not numbered. Apply order is documented, not encoded in filenames.

## `sql/sync/` — cloud-restore sync schema

Restores watch progress / watched history / library / collections / settings / profiles on reinstall
or fresh-TV login (canary-gated, closed by default). Each object is a triad:

```
<object>_setup.sql      idempotent create
<object>_teardown.sql   drop (data-destructive)
<object>_test.sql       ASSERT suite; run via the runner (rolled back per file). Uses \ir to reload its sibling setup.
```

Plus `sync_test_helpers.sql` (shared fixtures), `sync_canary_test.sql` (gate proof), `probe_sync_rpcs.sql` (detection probe).

`library_delta_setup.sql` / `library_delta_teardown.sql` are a setup/teardown PAIR (no `_test.sql`):
the upstream-0.8.1 library sync layer applied on top of `library_setup.sql` — append-only
`library_events` log + delta RPCs (`sync_push_library_items`, `sync_delete_library_items`,
`sync_get_library_delta_cursor`, `sync_pull_library_delta`), the `registered_devices` table +
`register_current_device` RPC, and an in-place replacement of `sync_push_library` /
`sync_push_library_for` that keeps the old fleet working while appending upsert events. Applied
to prod 2026-08-04; the teardown restores the pre-delta push bodies before dropping the new tables.

- **Deploy order, canary rollout, verification, rollback:** see [`../CLOUD-RESTORE-RUNBOOK.md`](../CLOUD-RESTORE-RUNBOOK.md).
- **Run the test suite:** `./run_sync_tests.sh <files…>` from the repo root (pass bare names — it resolves them under `sql/sync/`).

## `sql/member/` — member management schema

Allowlist / access kill-switch / device-limit / per-member addons / activity telemetry. Setup-mostly,
applied manually per their plans (`plans/MEMBER-ACCESS-PLAN.md`, `plans/2026-06-09-member-activity-telemetry.md`).
Not run by `run_sync_tests.sh`.
