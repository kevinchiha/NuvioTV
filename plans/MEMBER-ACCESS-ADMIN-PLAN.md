# Plan: wire the access kill-switch into admin.kevbox.dev (kevbox-admin)

> Companion to `MEMBER-ACCESS-PLAN.md` (server + TV client). This plan makes the kill-switch /
> device-limit manageable from the admin UI instead of hand-written SQL.

## Revision — gap analysis folded in (2026-06-09)

This plan was gap-analyzed against the **deployed** schema (`member_access_setup.sql`,
`member_device_setup.sql`), the released TV client, and the live `kevbox-admin` codebase + test
harness. The core design held up (schema/PKs/FKs match; `claim_device` honors `max_devices`; the
GRANT block is correct and sufficient; no sequence grant needed; BYPASSRLS covers RLS). The findings
below are now integrated into the relevant sections. The one **execution-blocking** item is the test
DB volume (§5.0); the rest are deploy-robustness, operator-correctness UX, test coverage, and doc
hardening. Items are tagged where they appear.

## Context

The remote access kill-switch + one-device-per-member limit are **live**: the Supabase schema
(`member_access`, `member_device`, `member_device_policy`, RPCs `get_access_verdict`/`claim_device`)
is deployed and the KevBox TV client shipped in 0.7.8-beta (1025). But `admin.kevbox.dev` shows a
reserved **"Access" placeholder** (`MemberDetail.tsx:54-58`) — so today the only way to disable a
member or manage their TVs is hand-written SQL. This makes that tab functional (gap S21).

Explored via a freshly-built graphify graph of kevbox-admin (`kevbox-admin/graphify-out/`) plus
targeted reads. The architecture is a clean monorepo: **core** (DB logic) → **web server** (Fastify
API) → **web SPA** (React) and a **CLI**, all sharing core.

## Key finding — the DB-access model (the load-bearing unknown, now resolved)

The admin connects as DB role **`kevbox_admin`**, which has **`rolbypassrls = true`** (verified
against prod). That's how it reads/writes `member_addon` despite RLS — it bypasses RLS entirely and
relies on explicit table GRANTs. The role + grants are a **manual operator step** documented in
`kevbox-admin/deploy/README.md:13-32` (the "Least-privileged DB role" block; grants at 25-29), not an
automated migration. Tables are owned by `postgres`.

**The admin NEVER calls `get_access_verdict()` / `claim_device()`.** Those RPCs resolve the member
from `auth.uid()` in a JWT — meaningless over the admin's direct pg connection (no JWT → `auth.uid()`
is null). All admin reads/writes are **direct, parameterized table queries keyed on `user_id`**,
relying on BYPASSRLS. (Tag: schema/rpc-semantics.)

**→ The new tables have no `kevbox_admin` grants yet.** BYPASSRLS bypasses RLS *policies*, **not**
table-level GRANTs — and the setup SQL revoked write grants from `anon`/`authenticated` and never
granted anything to `kevbox_admin`. So without the grant below, `getAccess` fails with
`permission denied for table member_access`. Step 1 is a small additive GRANT on prod:

```sql
grant select, insert, update, delete on public.member_access        to kevbox_admin;
grant select, insert, update, delete on public.member_device        to kevbox_admin;
grant select, insert, update, delete on public.member_device_policy to kevbox_admin;
```

No sequence grant is needed (all three tables use natural keys — `user_id` uuid PK, or composite
`(user_id, device_id)` — there is no serial/identity). `USAGE on schema public` is already held by
`kevbox_admin` from the existing role bootstrap (`deploy/README.md:24`).

**Where these grants live (drift risk — Tag: deploy/drift).** Rather than only documenting them in
the README (a third disjoint applied artifact alongside the two `*_setup.sql` files, with nothing
reconciling them on a DB rebuild/role-recreate), **fold the three `kevbox_admin` grants into the
idempotent `*_setup.sql` files** — those files already manage `revoke ... from anon, authenticated`
and are "safe to re-run", so applying the schema also applies the grants = one runnable source of
truth. Mirror them into the `deploy/README.md:13-32` block as well for the operator runbook.

## Operations the Access tab needs (all keyed on `user_id`)

- **Read** state: `member_access.active`, `member_device` rows (name + first/last seen), and
  `member_device_policy.max_devices` (absent row ⇒ 1).
- **Toggle `active`** — the kill-switch.
- **Set `max_devices`** — raise/lower the device cap. ⚠️ Lowering the cap does **not** evict seated
  devices (see §1 / §3): an already-bound device refreshes-and-allows in `claim_device` *before* the
  count check, so to actually reduce a member to N TVs you must also **Remove** the excess rows.
- **Remove a device** — deauthorize a TV (frees a slot so a replacement can claim).

## Implementation — mirror the existing Addons feature end-to-end

### 1. core — `packages/core/src/access.ts` (+ types in `types.ts`, export in `index.ts`)
Mirror `members.ts`/`addons.ts`: functions take `db: Db` first, run parameterized `db.query`, use
`validationError()` (statusCode 400) for bad input, return mapped objects. **Every mutation must be a
single atomic SQL statement** (upsert / scoped delete / delete-all) — production `db` is a `pg.Pool`
(autocommit per query), so no multi-statement delete-then-insert (re-introduces the gap-analysis M2
crash-window). The listed signatures are all single-statement; keep them that way. Concurrency is
**last-write-wins** (the upserts set `updated_at=now()` with no precondition) — acceptable for the
single-operator scope; documented here so it's a decision, not an accident.
```ts
export interface DeviceRow { deviceId: string; deviceName: string|null; firstSeen: string; lastSeen: string }
export interface AccessState { userId: string; active: boolean; maxDevices: number; devices: DeviceRow[] }
getAccess(db, userId): Promise<AccessState>          // active (no row⇒true), max_devices (no row⇒1), devices by last_seen desc
setActive(db, userId, active): Promise<AccessState>  // upsert member_access on (user_id) ... set active, updated_at=now()
setMaxDevices(db, userId, max): Promise<AccessState> // validate int>=1; upsert member_device_policy. Does NOT evict excess devices.
removeDevice(db, userId, deviceId): Promise<void>    // delete from member_device where user_id=$1 and device_id=$2 (scoped); throw notFound (404) if rowCount===0
removeAllDevices(db, userId): Promise<void>          // delete all of the member's device rows
```
`device_id` is an **opaque client-generated TEXT** id (not a numeric PK like `addonId`). `mapDeviceRow`
needs **no** `Number()` coercion (unlike `mapAddonRow`), and the route must **not** apply the
`Number.isInteger` guard used for `:addonId`. Add `mapDeviceRow` to `types.ts`. (Keep `MemberDetail`
unchanged — access is fetched separately so the existing addon tests/types are untouched.)

### 2. web server — `apps/web/src/server/routes/access.ts` + register in `app.ts`
Add `registerAccessRoutes(app, db)` mirroring `routes/addons.ts`. **Two edits to `app.ts`:** add
`import { registerAccessRoutes } from "./routes/access.js";` alongside the existing route imports
(`app.ts:5-8`), **and** call `registerAccessRoutes(api, opts.db)` inside the existing `/api` +
`requireAdmin` plugin block (`app.ts:56-62`).
- Every `:userId` route resolves the member via `getMember` first → **404** on unknown member (not a
  raw FK-500 / not a phantom `active=true`). This is mandatory for all four mutating routes, not just
  reads.
- `:deviceId` is opaque TEXT → validate **non-empty trimmed string** (400); do NOT `Number`-validate.
- `removeDevice` returns 404 when it deletes zero rows (no silent `{ok:true}` over a no-op — matches
  the project's anti-silent-success convention in `addons.ts` reorder).

Routes:
- `GET    /members/:userId/access`              → `{ access }`
- `PUT    /members/:userId/access/active`        `{active:boolean}` → `{ access }`
- `PUT    /members/:userId/access/max-devices`   `{maxDevices:number}` → `{ access }`
- `DELETE /members/:userId/devices/:deviceId`    → `{ ok: true }`  (404 if no such device)
- `DELETE /members/:userId/devices`              → `{ ok: true }`  (remove all)

### 3. web SPA
- **`lib/api.ts`**: add `getAccess`, `setActive`, `setMaxDevices`, `removeDevice`, `removeAllDevices`
  (+ re-export `AccessState`/`DeviceRow` types) following the existing `Api` method style.
- **`App.tsx`**: add `selectedAccess: AccessState | null` state. **Extend `reloadSelected`
  (`App.tsx:41-47`) to fetch member + access in parallel (`Promise.all`) and set both `selected` and
  `selectedAccess`** — this is the *single* fetch point. `afterMutate` already calls `reloadSelected`,
  so access re-fetches automatically — do **NOT** add a second `getAccess` there (double-fetch). Verify
  `onBulkSwap` (`App.tsx:134`) — which calls `reloadSelected` directly — also refreshes access. Add
  handlers `onSetActive` (route the **disable** action through `ConfirmModal` via `setPending`, like
  `onReset`), `onSetMaxDevices`, `onRemoveDevice`/`onRemoveAllDevices` (confirm). Pass `access` +
  handlers into `MemberDetail`.
- **`components/AccessTab.tsx`** (new): replace the placeholder branch in `MemberDetail.tsx:54-58`.
  Renders:
  - **Active status + Enable/Disable** (Disable is `danger`, confirmed). Next to it, an interpreted
    **lock-timing hint** computed from `last_seen`: *"locks within ~2 min while the TV is awake &
    online; up to ~5 min if offline; a sleeping/backgrounded TV locks when it next wakes."* (Numbers
    are the deployed `AccessControl.kt` constants `CHECK_INTERVAL_MS=2min`, `GRACE_MS=5min`.) The tab
    reflects **intent, not live enforcement** — if a panic-button rollback is engaged (RPCs redefined
    to always-allow), toggles here will have no effect; note this near the Disable control.
  - **Device list**: render `device_name` **AND `first_seen` AND `last_seen`** (relative + absolute)
    **plus a short `device_id` suffix**, sorted by `last_seen` desc, marking the most-recent as
    likely-active. Device names are model strings (`"${Build.MANUFACTURER} ${Build.MODEL}"`,
    `DeviceGuardService.kt:94`) and are **non-unique** across identical KevBox hardware — first/last
    seen + id suffix are what let the operator pick the *right* TV and avoid deauthorizing the active
    one. Each row has a **Remove** button; the confirm message names the device(s)/count and notes
    removal is **non-undoable** (a returning/cleared TV mints a new UUID, so the binding is gone).
  - **`max_devices` control + "N of M devices used"**. When `M < N` (cap lowered below seated count),
    show a warning: *"N devices still authorized; remove M to enforce — lowering the cap does not
    evict."*
  - **"Remove all devices"** action + a short **replacement-TV procedure** note: *power off / uninstall
    the old TV first (or it re-claims the slot within ~2 min), or disable→remove→re-enable, or
    temporarily raise max-devices to 2; otherwise the old TV silently re-grabs the only slot.*
  - Keep `MemberDetail` lean by delegating to this component (mirrors how `DebridForm` is split out).
- **Member list (`MemberList.tsx`)**: for v1, access state is **intentionally detail-pane-only** — the
  list keeps showing only addon/debrid (no per-member fetch of access on the list). (Optional later:
  extend `MemberSummary`/`listMembers` with `active` + `deviceCount` and badge disabled/over-limit
  members for an at-a-glance fleet view.)

### 4. CLI — `packages/cli/src/{actions.ts,index.ts}` (+ `format.ts`)  [included]
Add commander commands mirroring the existing ones: `access <ref>` (show active + devices +
max_devices), `access-disable <ref>` / `access-enable <ref>`, `access-max-devices <ref> <n>`,
`device-remove <ref> <deviceId>`, `device-remove-all <ref>`. Each `actionX(pool, …, sink)` **resolves
`ref` via `getMember`/`requireMember` first** (404/error on unknown — exactly like the existing CLI
actions), so the `getAccess` fail-open default (`no row ⇒ active=true`) never masquerades as a real
member for a typo'd ref. Add `formatAccess`/`formatDevices` helpers in `format.ts`.

### 5. tests (vitest, per package)

#### 5.0 Test DB volume must be re-initialized (EXECUTION BLOCKER — Tag: test-harness)
The test DB loads `packages/core/test/schema.sql` **only via `/docker-entrypoint-initdb.d`**
(`docker-compose.yml:10`), which Postgres runs **only on a fresh volume**. The running container was
already initialized from the *old* schema (member_addon only), so **editing `schema.sql` does nothing
to a live container** — new access tests fail with `relation "public.member_access" does not exist`.
**After editing `schema.sql`, recreate the volume:** `docker compose down -v && docker compose up -d
test-db` (or psql-apply the new DDL to the running DB). Do this *before* the "tests green" gate. (Note:
the companion plans' teardown is `docker compose down` without `-v`, which preserves the stale volume —
don't rely on it.)

#### 5.1 Test schema fidelity
- **`packages/core/test/schema.sql`**: add the three tables, copying the **exact** prod DDL —
  explicitly carry the load-bearing constraints the upserts/defaults depend on: `user_id` PK on
  `member_access` and `member_device_policy`, composite PK `(user_id, device_id)` on `member_device`,
  the `not null default true` / `default 1` column defaults, and `references auth.users(id) on delete
  cascade`. (`setActive`'s `on conflict (user_id)` errors `42P10` without the unique/PK.)
- Do **NOT** port the `seed_member_access` SECURITY DEFINER trigger or the back-fill into the test
  schema — their absence is exactly what keeps test members unseeded, making the `no row ⇒ active=true`
  / `max=1` default path testable. (A future schema.sql that mirrors the trigger would silently break
  those default tests.)
- The RPCs (`claim_device`/`get_access_verdict`) are **out of test scope** — access core reads/writes
  tables directly. Seed `member_device` / `member_access` rows by **direct INSERT** in tests (the
  established `addons.test.ts`/`actions.test.ts` idiom), not via an RPC.

#### 5.2 Test cases — `packages/core/test/access.test.ts` (mirror `addons.test.ts`, rollback-scoped `db`)
- `getAccess` defaults (no rows ⇒ active=true / max=1) **and** the row-**FOUND** path (insert an
  `active=false` `member_access` row + a `max_devices=2` policy row — this is the path prod almost
  always hits, since members are seeded).
- `setActive` upsert: the **insert** branch (no prior row) and the **update** branch; double-disable is
  idempotent (no-op → active=false).
- `setMaxDevices` validation (reject `<1`); **lowering below current device count succeeds but does NOT
  evict** (assert both device rows survive) — and decide/document the resulting "N of M" over-limit
  display.
- `removeDevice` scoping — including a **cross-member** case: `removeDevice(memberA, memberB'sDeviceId)`
  must leave memberB's row intact.
- `removeAllDevices` clears only the target member's rows (destructive op — must have coverage).
- **`apps/web/test/server/access.routes.test.ts`**: route happy-paths + 400 (empty `deviceId`,
  `maxDevices<1`) + 404 (unknown member, no-such-device), mirror `addons.routes.test.ts`.
- **CLI tests** [optional]: mirror the *existing CLI* tests — shared-pool + `afterEach` suffix-cleanup
  (NOT rollback; that's the core-package model), relying on `on delete cascade`.
- Optional: a web component test for `AccessTab`.

### 6. docs + deploy
- **Grants as one source of truth**: fold the three `kevbox_admin` grants into the idempotent
  `member_access_setup.sql` / `member_device_setup.sql` files, **and** mirror them into the
  "Least-privileged DB role" block at `deploy/README.md:13-32` (append after the existing
  `member_addon` grant at line 25). Note that `USAGE on schema public` is presumed from the role
  bootstrap and no sequence grant is needed (natural keys).
- **Audit trail — explicit decision**: the kill-switch / remove-all are the most consequential admin
  actions, but the project already shipped the analogous destructive *addon* ops with **no audit table**
  (prior gap-analysis L3, accepted for single-operator scope). **Decision: match that precedent — no
  audit table for v1.** (If desired later, log access mutations at info level with the admin email from
  `requireAdmin`'s verified JWT, which `journald` already captures.)
- Build + test per the repo's scripts (incl. §5.0 volume rebuild), then **`./deploy.sh`** to
  admin.kevbox.dev (separate, confirmed step — it's the outward-facing one).

## Verification

- **Prod GRANT smoke test (HARD pre-deploy gate — Tag: deploy/silent-500):** as `kevbox_admin`,
  confirm `select`/`update` on `member_access` and `select`/`delete` on `member_device` succeed. Run
  the GRANT itself **as the table owner (`postgres` / Supabase SQL editor)**, not as `kevbox_admin`.
  This must pass **before** `./deploy.sh` — `deploy.sh`'s `/healthz` is only `select 1` and will **not**
  catch a missing grant, so a green deploy can still 500 on the Access tab (BYPASSRLS does **not**
  substitute for the table GRANT). Optionally add a one-off post-deploy `curl /api/members/<id>/access`.
- **Tests:** vitest green in `packages/core`, `apps/web`, `packages/cli` (after §5.0 volume rebuild).
- **Local web run:** start the server against a test DB (schema.sql) or prod, exercise the Access
  tab: see active=Enabled, devices (with first/last seen + id suffix), max_devices; toggle Disable →
  `member_access.active` flips (verify via psql; the released TV client then locks per the timing hint
  — ~2 min awake, up to ~5 min offline, on-wake if backgrounded); set max_devices=2; remove a device;
  confirm the "N of M" over-limit warning when lowering below seated count.
- **Post-deploy:** open `admin.kevbox.dev` → member → Access tab → end-to-end toggle.

## Decisions (locked)

- **CLI:** included alongside the web tab (full parity); CLI resolves `ref` via `getMember` first.
- **Execution boundary:** implement core/server/SPA/CLI + tests → commit/push on `kevbox` → run the
  additive `kevbox_admin` GRANT on prod Supabase (as table owner) → **GRANT smoke test gate** →
  `./deploy.sh` to admin.kevbox.dev. Confirm immediately before the deploy step (the only
  outward-facing action).
- **Device removal:** both per-device remove and a "remove all devices" shortcut (handy for approving a
  replacement TV under the strict-1 limit). Removal is **non-undoable** (returning TV mints a new UUID);
  the confirm message names the device(s). **Replacement requires the old TV to be off/disabled or a
  temporary max-devices=2**, else it re-claims within ~2 min.
- **Lowering `max_devices` does not evict** seated devices — operator must also Remove excess rows;
  surfaced as a UI warning.
- **Access state is detail-pane-only** for v1 (member list unchanged).
- **No audit table** for v1 (matches the addon-ops precedent / single-operator scope).
- **Concurrency:** last-write-wins on the upserts (acceptable for single-operator scope).

## Build order (when executing)

core (`access.ts` + single-atomic-statement mutations + types) → server (`routes/access.ts` +
`app.ts` import **and** registration, getMember 404 guards, deviceId/maxDevices validation) → SPA
(`api.ts`, `App.tsx` reloadSelected single-fetch, `AccessTab.tsx`) → CLI → **`schema.sql` + `docker
compose down -v && up -d test-db`** → tests green across all three packages → fold grants into
`*_setup.sql` + `deploy/README.md:13-32` → commit/push → prod GRANT (as owner) → **GRANT smoke test
gate** → confirm → `./deploy.sh`.

---

## Appendix — patterns to mirror (file:line)

- **DB role / RLS:** `kevbox_admin` has `BYPASSRLS` + table grants; documented
  `deploy/README.md:13-32` (the "Least-privileged DB role" block; `create role ... bypassrls` at 22,
  `member_addon` grant at 25, `auth.users` column grant at 29, schema USAGE at 24).
- **Deployed schema (source of truth):** `member_access_setup.sql` (table 10-14: `user_id` uuid PK →
  `auth.users(id)` cascade, `active` default true, `updated_at`; verdict RPC 35-52; seed trigger 61-69;
  back-fill 72; "no view" warning 83-85; panic rollback 91-92). `member_device_setup.sql`
  (`member_device` 11-18: composite PK; `member_device_policy` 28-32: `max_devices` default 1;
  `claim_device` 45-77 — honors `max_devices` at 64-68, refresh-and-allow bound device at 59-62 *before*
  the count check; device-name disambiguation note 86; panic rollback 96).
- **TV client constants:** `DeviceGuardService.kt:94` (`device_name = MANUFACTURER MODEL`),
  `AccessControl.kt:18` (`CHECK_INTERVAL_MS = 2min`), `AccessControl.kt:15` (`GRACE_MS = 5min`).
- **core module shape:** `packages/core/src/addons.ts` (parameterized `db.query`, `validationError`),
  `members.ts` (`getMember(db, ref)` resolves email|uuid via `public.kevbox_auth_users`; `requireMember`
  guard idiom), `reset.ts:15-28` (single-statement atomic CTE — the Pool-safe mutation precedent),
  `types.ts` (`Db = Pick<Pool|PoolClient,"query">`, `mapAddonRow`), `index.ts` (barrel export).
- **server route shape:** `apps/web/src/server/routes/addons.ts` (`registerAddonRoutes(app, db)`,
  404 via `getMember`, anti-silent-success on the reorder route), wired in `app.ts:56-62` under `/api` +
  `requireAdmin` (route imports at `app.ts:5-8`). Sanitizing error handler at `app.ts:35-39` (5xx →
  opaque "internal error" — which is why the GRANT smoke test matters: a missing grant surfaces as an
  un-diagnosable 500).
- **SPA shape:** `apps/web/src/web/App.tsx` (`reloadSelected:41-47`, `afterMutate:74-79`,
  `onBulkSwap:134`, owns `selected`, `busy`, `pending`/`ConfirmModal`, `withBusy`), `lib/api.ts`
  (`Api` class, bearer token), `components/MemberDetail.tsx:54-58` (placeholder/tab state) +
  `DebridForm.tsx` (split-out sub-form precedent).
- **CLI shape:** `packages/cli/src/index.ts` (commander `program.command(...).action(withPool(...))`),
  `actions.ts` (`actionX(pool, …, sink)`, `requireMember` resolves ref), `format.ts`. Test isolation:
  shared pool + `afterEach` suffix-cleanup (not rollback).
- **tests:** `packages/core/test/{schema.sql,addons.test.ts}` (rollback-scoped db),
  `apps/web/test/server/addons.routes.test.ts`, `docker-compose.yml:10` (initdb mount — fresh-volume
  only).
- **prior gap analysis:** `kevbox-admin/docs/superpowers/2026-06-09-gap-analysis.md` (M2 Pool-path
  atomicity, M1 member-existence 404 contract, L3 no-audit-log decision, L5 last-write-wins).
- **graph:** `kevbox-admin/graphify-out/graph.json` + `GRAPH_REPORT.md`.
