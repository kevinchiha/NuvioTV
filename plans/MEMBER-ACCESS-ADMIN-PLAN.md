# Plan: wire the access kill-switch into admin.kevbox.dev (kevbox-admin)

> Companion to `MEMBER-ACCESS-PLAN.md` (server + TV client). This plan makes the kill-switch /
> device-limit manageable from the admin UI instead of hand-written SQL.

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
`kevbox-admin/deploy/README.md:22-29`, not an automated migration. Tables are owned by `postgres`.

**→ The new tables have no `kevbox_admin` grants yet.** So step 1 is a small additive GRANT on prod:

```sql
grant select, insert, update, delete on public.member_access        to kevbox_admin;
grant select, insert, update, delete on public.member_device        to kevbox_admin;
grant select, insert, update, delete on public.member_device_policy to kevbox_admin;
```
BYPASSRLS already covers the RLS policies, so no policy changes are needed. Add these to the
"Least-privileged DB role" block in `deploy/README.md` as the version-controlled source of truth.

## Operations the Access tab needs (all keyed on `user_id`)

- **Read** state: `member_access.active`, `member_device` rows (name + first/last seen), and
  `member_device_policy.max_devices` (absent row ⇒ 1).
- **Toggle `active`** — the kill-switch.
- **Set `max_devices`** — raise/lower the device cap.
- **Remove a device** — deauthorize a TV (frees a slot so a replacement can claim).

## Implementation — mirror the existing Addons feature end-to-end

### 1. core — `packages/core/src/access.ts` (+ types in `types.ts`, export in `index.ts`)
Mirror `members.ts`/`addons.ts`: functions take `db: Db` first, run parameterized `db.query`, use
`validationError()` (statusCode 400) for bad input, return mapped objects.
```ts
export interface DeviceRow { deviceId: string; deviceName: string|null; firstSeen: string; lastSeen: string }
export interface AccessState { userId: string; active: boolean; maxDevices: number; devices: DeviceRow[] }
getAccess(db, userId): Promise<AccessState>          // active (no row⇒true), max_devices (no row⇒1), devices by last_seen desc
setActive(db, userId, active): Promise<AccessState>  // upsert member_access on (user_id) ... set active, updated_at=now()
setMaxDevices(db, userId, max): Promise<AccessState> // validate int>=1; upsert member_device_policy
removeDevice(db, userId, deviceId): Promise<void>    // delete from member_device where user_id=$1 and device_id=$2 (scoped)
removeAllDevices(db, userId): Promise<void>          // delete all of the member's device rows
```
Add `mapDeviceRow` to `types.ts`. (Keep `MemberDetail` unchanged — access is fetched separately so
the existing addon tests/types are untouched.)

### 2. web server — `apps/web/src/server/routes/access.ts` + register in `app.ts`
Add `registerAccessRoutes(app, db)` mirroring `routes/addons.ts` (validate→400, unknown member→404
via `getMember`, return JSON). Register it inside the existing `/api` + `requireAdmin` block in
`app.ts:56-62`.
- `GET    /members/:userId/access`              → `{ access }`
- `PUT    /members/:userId/access/active`        `{active:boolean}` → `{ access }`
- `PUT    /members/:userId/access/max-devices`   `{maxDevices:number}` → `{ access }`
- `DELETE /members/:userId/devices/:deviceId`    → `{ ok: true }`
- `DELETE /members/:userId/devices`              → `{ ok: true }`  (remove all)

### 3. web SPA
- **`lib/api.ts`**: add `getAccess`, `setActive`, `setMaxDevices`, `removeDevice`, `removeAllDevices`
  (+ re-export `AccessState`/`DeviceRow` types) following the existing `Api` method style.
- **`App.tsx`**: add `selectedAccess: AccessState | null` state; fetch it in `reloadSelected`
  (parallel to `getMember`) and re-fetch in `afterMutate`; add handlers `onSetActive` (route the
  **disable** action through `ConfirmModal` via `setPending`, like `onReset`), `onSetMaxDevices`,
  `onRemoveDevice`/`onRemoveAllDevices` (confirm). Pass `access` + handlers into `MemberDetail`.
- **`components/AccessTab.tsx`** (new): replace the placeholder branch in `MemberDetail.tsx:54-58`.
  Renders: active status + Enable/Disable button (Disable is `danger`), device list (name, last
  seen, **Remove**), `max_devices` control + "N of M devices used", and a "remove all devices"
  action. Keep `MemberDetail` lean by delegating to this component (mirrors how `DebridForm` is
  split out).

### 4. CLI — `packages/cli/src/{actions.ts,index.ts}` (+ `format.ts`)  [included]
Add commander commands mirroring the existing ones: `access <ref>` (show active + devices +
max_devices), `access-disable <ref>` / `access-enable <ref>`, `access-max-devices <ref> <n>`,
`device-remove <ref> <deviceId>`, `device-remove-all <ref>`. Each `actionX(pool, …, sink)` calls
core; add an `formatAccess`/`formatDevices` helper in `format.ts`.

### 5. tests (vitest, per package)
- **`packages/core/test/schema.sql`**: add the three tables (mirror prod DDL) so the local test DB
  has them.
- **`packages/core/test/access.test.ts`**: getAccess defaults (no rows ⇒ active=true/max=1),
  setActive upsert, setMaxDevices validation (reject <1), removeDevice scoping. Mirror
  `addons.test.ts` (rollback-scoped `db`).
- **`apps/web/test/server/access.routes.test.ts`**: route happy-paths + 400/404, mirror
  `addons.routes.test.ts`.
- Optional: a web component test for `AccessTab` and a CLI test, mirroring existing ones.

### 6. docs + deploy
- **`deploy/README.md`**: add the three GRANTs to the "Least-privileged DB role" block.
- Build + test per the repo's scripts, then **`./deploy.sh`** to admin.kevbox.dev (separate,
  confirmed step — it's the outward-facing one).

## Verification

- **Prod GRANT smoke test:** as `kevbox_admin`, confirm `select`/`update` on `member_access` and
  `select`/`delete` on `member_device` succeed (proves the grants work; BYPASSRLS handles RLS).
- **Tests:** vitest green in `packages/core`, `apps/web`, `packages/cli`.
- **Local web run:** start the server against a test DB (schema.sql) or prod, exercise the Access
  tab: see active=Enabled, devices, max_devices; toggle Disable → `member_access.active` flips
  (verify via psql; the released TV client then locks within ~2 min); set max_devices=2; remove a
  device.
- **Post-deploy:** open `admin.kevbox.dev` → member → Access tab → end-to-end toggle.

## Decisions (locked)

- **CLI:** included alongside the web tab (full parity).
- **Execution boundary:** implement core/server/SPA/CLI + tests → commit/push on `kevbox` → run the
  additive `kevbox_admin` GRANT on prod Supabase → `./deploy.sh` to admin.kevbox.dev. Confirm
  immediately before the deploy step (the only outward-facing action).
- **Device removal:** both per-device remove and a "remove all devices" shortcut (handy for
  approving a replacement TV under the strict-1 limit).

## Build order (when executing)

core (`access.ts` + types + schema.sql) → server (`routes/access.ts` + `app.ts`) → SPA (`api.ts`,
`App.tsx`, `AccessTab.tsx`) → CLI → tests green across all three packages → `deploy/README.md`
grants → commit/push → prod GRANT → confirm → `./deploy.sh`.

---

## Appendix — patterns to mirror (file:line)

- **DB role / RLS:** `kevbox_admin` has `BYPASSRLS` + table grants; documented `deploy/README.md:22-29`.
- **core module shape:** `packages/core/src/addons.ts` (parameterized `db.query`, `validationError`),
  `members.ts` (`getMember(db, ref)` resolves email|uuid via `public.kevbox_auth_users`),
  `types.ts` (`Db = Pick<Pool|PoolClient,"query">`, `mapAddonRow`), `index.ts` (barrel export).
- **server route shape:** `apps/web/src/server/routes/addons.ts` (`registerAddonRoutes(app, db)`,
  404 via `getMember`), wired in `apps/web/src/server/app.ts:56-62` under `/api` + `requireAdmin`.
- **SPA shape:** `apps/web/src/web/App.tsx` (owns `selected`, `busy`, `pending`/`ConfirmModal`,
  `withBusy`, `afterMutate`), `lib/api.ts` (`Api` class, bearer token), `components/MemberDetail.tsx`
  (tab state) + `DebridForm.tsx` (split-out sub-form precedent).
- **CLI shape:** `packages/cli/src/index.ts` (commander `program.command(...).action(withPool(...))`),
  `actions.ts` (`actionX(pool, …, sink)`), `format.ts`.
- **tests:** `packages/core/test/{schema.sql,addons.test.ts}` (rollback-scoped db),
  `apps/web/test/server/addons.routes.test.ts`.
- **graph:** `kevbox-admin/graphify-out/graph.json` + `GRAPH_REPORT.md` (built this session).
