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

- **Deploy order, canary rollout, verification, rollback:** see [`../CLOUD-RESTORE-RUNBOOK.md`](../CLOUD-RESTORE-RUNBOOK.md).
- **Run the test suite:** `./run_sync_tests.sh <files…>` from the repo root (pass bare names — it resolves them under `sql/sync/`).

## `sql/member/` — member management schema

Allowlist / access kill-switch / device-limit / per-member addons / activity telemetry. Setup-mostly,
applied manually per their plans (`plans/MEMBER-ACCESS-PLAN.md`, `plans/2026-06-09-member-activity-telemetry.md`).
Not run by `run_sync_tests.sh`.
