# KevBox Admin — Design

- **Date:** 2026-06-09
- **Status:** Approved (design); implementation not started
- **Owner:** Kevin (operator / sole admin)
- **Related (in the NuvioTV/KevBox repo):** `member_addon_setup.sql`, `plans/done/MEMBER-CONFIG-PLAN.md`, `MEMBER-DEBRID-ONBOARDING.md`

## 1. Goal

A clean admin surface for the KevBox TV **member-addon** system so the operator can manage each family
member's Stremio addons without hand-editing the Supabase console. Today the only admin surface is the
Supabase dashboard (Table Editor + a read-only `member_addon_v` view + the SQL editor), which is clunky:
rows are keyed by `user_id` (no email), addon URLs are very long, and there's no validation, bulk tooling,
or undo.

Deliver **two correlated front-ends over one shared core**:
- a **web admin** (reachable from anywhere, incl. phone), and
- a **local CLI**.

## 2. Non-goals

- No changes to the Android app or the device-side apply logic (that ships separately; the app only ever
  *reads* `member_addon`).
- No realtime/push to devices — member config still applies on app open (unchanged).
- v1 does not implement the `member_access` kill-switch, but the UI is architected so it can be added as a
  second tab later.
- Not a multi-tenant/multi-admin product — single operator.

## 3. Locked decisions (from brainstorming)

| Decision | Choice |
|---|---|
| Access model | **Hosted** — reachable from any browser (incl. phone) |
| Hosting | **persovps** (the VPS already serving `tv.kevbox.dev`), behind nginx |
| Auth | **Supabase Auth** admin login; server gates on `email ∈ ADMIN_EMAILS` |
| Stack | **TypeScript monorepo** — shared `core` + `cli` + `web` (Fastify + Vite/React) |
| Scope (v1) | Member overview + per-member editing + reset-to-defaults + guided debrid onboarding + **bulk ops** + **extensible for the future kill-switch** |
| Location | A top-level **`kevbox-admin/`** folder **inside the NuvioTV repo** (self-contained npm monorepo). Additive & fork-only (upstream has no such folder), so it doesn't complicate upstream merges. |
| Subdomain | `admin.kevbox.dev` |
| Admin email | `kevin.chiha@gmail.com` (via `ADMIN_EMAILS`) |

## 4. Architecture

Monorepo (npm workspaces) with three packages; the CLI and web import the **same** `core` — this is the
"correlation" that keeps them in sync.

```
kevbox-admin/
  packages/
    core/      # operations + types; pure, takes an injected pg client; no secrets
    cli/       # commander CLI (local-trusted); reads SUPABASE_DB_URL from env/local.properties
  apps/
    web/       # Fastify API + Vite/React SPA; Supabase-Auth gate; serves the UI
  docs/
  deploy.sh    # build + scp + restart systemd (mirrors NuvioTV release.sh pattern)
```

### 4.1 Data layer
All data access (both front-ends) is **direct Postgres via `SUPABASE_DB_URL`**, server-side / local only —
never in the browser. Direct Postgres (vs. the Supabase REST client) is chosen because the admin must join
`auth.users` to resolve emails and run bulk/SQL operations that the locked-down `member_addon_v` view and
anon/authenticated REST role can't do. `core` operates on an injected `pg` `Pool`/client so it holds no
connection or secret itself.
- **Least-privileged role (not superuser):** the hosted server's `SUPABASE_DB_URL` uses a dedicated
  `kevbox_admin` role (manage `member_addon`, read `auth.users(id,email,created_at)`, `bypassrls` so the
  admin is cross-member) — **never the `postgres` superuser**, so compromising the internet-facing process
  does not hand over the whole project. SQL is in `deploy/README.md`. (The local CLI is operator-trusted and
  may use any working connection string.)
- **Connection host:** use the Supabase **Session pooler** host (port 5432, IPv4-reachable, prepared
  statements intact). The direct `db.<ref>.supabase.co` host is IPv6-only and fails on an IPv4-only VPS; the
  transaction pooler (6543) breaks `pg`'s prepared statements.

### 4.2 `packages/core` — operations (signatures, indicative)
```
listMembers(db): MemberSummary[]                      // email, userId, createdAt, addonCount, hasDebrid
                                                      //   hasDebrid = has any addon NOT in default_member_addons()
getMember(db, ref): MemberDetail                      // ref = email | userId; rows ordered by sort_order, id
addAddon(db, userId, { url, enabled?, sortOrder? })
updateAddon(db, addonId, { url?, enabled? })
setEnabled(db, addonId, enabled)
reorder(db, userId, orderedAddonIds[])                // rewrites sort_order
deleteAddon(db, addonId)
resetToDefaults(db, userId)                           // delete rows; re-seed from default_member_addons()
onboardDebrid(db, userId, { premiumizeKey, aiostreamsUrl }) // builds Torrentio URL + inserts AIOStreams
bulkAddAddon(db, { url, sortOrder }, confirm)         // add to ALL members
bulkSwapUrl(db, { fromUrl, toUrl }, confirm)          // swap URL across ALL members
```
Plus helpers: `buildTorrentioUrl(premiumizeKey)` and the debrid templates (mirror
`MEMBER-DEBRID-ONBOARDING.md`).

### 4.3 Data integrity / single source of truth
- **`resetToDefaults` is ONE atomic statement** — a writable CTE that upserts `default_member_addons()`
  (`on conflict (user_id,url) do update`) and then deletes the member's non-default rows. It reuses the DB
  function that already is the source of truth for the 4 universal defaults (so the admin does **not** become
  a 4th hardcoded copy alongside Kotlin `DefaultContent`, Kotlin `AddonPreferences.getDefaultAddons`, and the
  SQL helper). Single-statement = safe on a `pg.Pool` (no autocommit gap), idempotent (no unique violation on
  re-run), and a failure can only leave an *incomplete* (re-runnable) reset — **never a member with zero
  addons**, which a naive delete-then-insert risks.
- **Debrid templates** (Torrentio query string with `premiumize=<KEY>`; AIOStreams = member's full URL at
  sort_order 5) are not in the DB, so they live in `core` and must be kept in sync with
  `MEMBER-DEBRID-ONBOARDING.md`. A comment in `core` points to that runbook.
- All inserts use the table's `unique(user_id, url)` (`on conflict do nothing` / upsert) for idempotency.

### 4.4 `packages/cli`
`commander`-based; commands map 1:1 to core ops, e.g.:
```
kevbox-admin list
kevbox-admin show <email>
kevbox-admin add <email> <url> [--disabled] [--sort N]
kevbox-admin reorder <email>            # interactive
kevbox-admin toggle <addonId>
kevbox-admin reset <email>
kevbox-admin onboard-debrid <email> --premiumize <key> --aiostreams <url>
kevbox-admin bulk-add <url> --sort 99 --yes
kevbox-admin bulk-swap <fromUrl> <toUrl> --yes
```
Reads `SUPABASE_DB_URL` from env, falling back to the NuvioTV `local.properties` path. Local-trusted: no
login (the DB URL in your env *is* the credential).

### 4.5 `apps/web`
- **Fastify** server: serves the built Vite/React SPA + a `/api/*` JSON layer that calls `core`. Also exposes
  an unauthenticated **`GET /healthz`** (runs `select 1`; the deploy smoke-check) so a green deploy proves DB
  connectivity, not just static serving. Request logging is on (journald); a `setErrorHandler` collapses 5xx
  to a generic message so `pg` internals (query/role/host) never leak to clients. Rate-limiting + security
  headers are applied at nginx (the SPA's Supabase login hits Supabase directly, which throttles its own auth).
- **Member-not-found contract:** the per-member mutating routes (`add`/`reset`/`debrid`/`reorder`) resolve the
  member first and return **404** for an unknown id — matching the CLI, instead of a 500 (FK violation) or a
  misleading 200. The single Fastify-server build is a self-contained **esbuild bundle** (`dist/server.js`).
- **Auth flow:** the SPA uses the Supabase JS client (anon key, public) to sign the admin in with
  email/password → JWT. The SPA sends `Authorization: Bearer <jwt>` on every `/api/*` call. A Fastify
  pre-handler verifies the JWT via the Supabase JS client (`auth.getUser(jwt)`, anon key) and checks
  `user.email ∈ ADMIN_EMAILS`; only then does the
  handler run a `core` op against the pg pool (server env `SUPABASE_DB_URL`). Regular family members (who
  have accounts in the same project) are rejected — they're authenticated but not in `ADMIN_EMAILS`.
- **UI (v1):**
  - **Member overview** — table of all members (email, created, # addons, has-debrid).
  - **Per-member panel** — list of that member's addons with inline enable/disable, drag-reorder, edit URL,
    delete, add; buttons for **reset-to-defaults** and **guided debrid onboarding** (key + AIOStreams URL
    inputs that preview the resulting rows before saving).
  - **Bulk ops** — add-to-everyone / swap-URL-everywhere, each behind a "type CONFIRM" modal.
  - Layout = member list (left) + per-member detail (right), so a future **Access / kill-switch** tab drops
    in alongside "Addons" in the detail pane without restructuring.

### 4.6 Safety
- **Bulk ops require explicit confirmation** (CLI `--yes`; web "type CONFIRM" modal): every change mirrors to
  all TVs on next open and there is no device-side undo.
- **Snapshot-before-bulk ships in v1** (moved up from v1.1): because `bulkSwapUrl` is destructive across
  *every* member with no device-side undo, each bulk op first captures a JSON snapshot of **all**
  `member_addon` rows (`core.snapshotAllAddons`). The CLI writes it to a timestamped file
  (`KEVBOX_SNAPSHOT_DIR`, default OS temp); the web returns it in the bulk response for the SPA to download.
  Confirmation stops accidental clicks; the snapshot is the backstop for *wrong inputs*.
- Input validation in `core`: URL must be a **syntactically valid `http(s)` URL** (not merely non-empty —
  a typo'd URL otherwise mirrors silently to a TV); integer sort_order; member exists.

## 5. Auth & config (env)
Server (`apps/web`, on persovps):
- `SUPABASE_DB_URL` — direct Postgres (data access); the **`kevbox_admin` least-privileged role** via the
  **Session pooler** host (see §4.1), never the `postgres` superuser / IPv6-only direct host.
- `SUPABASE_URL`, `SUPABASE_ANON_KEY` — to verify the admin's Supabase JWT
- `ADMIN_EMAILS` — comma-separated allowlist (= `kevin.chiha@gmail.com`)
- `PORT` — local port nginx proxies to
- `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY` — **baked into the SPA at build time**. Rotating the anon key
  therefore requires editing these + the server env **and re-running `deploy.sh`** (a restart alone keeps the
  old key in the shipped JS). DB-password / `ADMIN_EMAILS` rotation is an env edit + restart. (See
  deploy/README.md "Rotating secrets".)

CLI (local): `SUPABASE_DB_URL` (or auto-read from the NuvioTV `local.properties`). Operator-trusted, so it may
use any working connection string. `KEVBOX_SNAPSHOT_DIR` (optional) overrides where bulk snapshots are written.

## 6. Deploy (persovps)
- Build `web`: Vite SPA (`dist/public`) + a single self-contained **esbuild server bundle** (`dist/server.js`,
  all deps incl. `@kevbox-admin/core` inlined) — so the artifact is just `dist/` with **no `node_modules`/`npm ci`**
  on the VPS. Run the Fastify process as a **systemd** service (least-priv user, hardened unit) on loopback.
- **nginx** reverse-proxies `admin.kevbox.dev` → that port with **rate-limiting + security headers**; **certbot** for TLS.
- `deploy.sh`: build → `scp` `dist/` to persovps → atomic `current` symlink swap → restart the unit → smoke-check
  **`/healthz`** (proves DB connectivity, not just static serving). Mirrors the existing `release.sh` workflow.
- Secrets live only in the server's env file on the VPS (not in git). The DB role is least-privileged (§4.1).

## 7. Testing
- **`vitest`** on `core` against a real throwaway Postgres (docker), each test wrapped in a transaction that
  rolls back (fast, isolated, real SQL). NB: the docker test schema intentionally omits Supabase RLS/roles and
  the seed trigger — those are exercised by the live smoke test below, not the unit suite.
- One **end-to-end smoke test** (manual ops check): create a throwaway `auth.users` row → confirm the seed
  trigger fires → admin `list`/`show`/`add`/`reset` → delete the user (cascades). **Run against a *disposable*
  Supabase project (not the live family project), with guaranteed teardown** — directly inserting `auth.users`
  can violate auth invariants (missing `auth.identities`/`encrypted_password`) and an aborted run would orphan
  rows in prod. A wrapping try/finally must delete the test user even on assertion failure.
- CLI: a couple of integration tests invoking commands against the test DB.
- *No CI yet* (every test needs a docker Postgres and the smoke test hits a live project) — runs are manual;
  worth adding a GitHub Action spinning up `postgres:16` for the `core`/`cli`/web-server suites, especially
  since this fork periodically merges upstream NuvioTV.

## 8. Open items / future
- **`member_access` kill-switch** — add an "Access" tab in the per-member detail pane + a `member_access`
  table (separate plan, `plans/MEMBER-ACCESS-PLAN.md`). Same Supabase-public-view-RLS caveat applies to any new
  table/view.
- **Concurrency is last-write-wins** (accepted v1 limitation): the table has `updated_at` but no optimistic-lock
  precondition, so two tabs / web+CLI editing the same member can silently clobber. Tolerable for a single
  operator; a future `updated_at`/`If-Match` check → 409 would harden it.
- **No audit log** of destructive actions (bulk/reset/delete). Acceptable single-operator; a small append-only
  `admin_audit` table would make a fat-finger or compromise diagnosable after the fact.
- Multi-admin (more than one `ADMIN_EMAILS` entry) already supported by the allowlist.

## 9. References
- Schema + hardened grants: `member_addon_setup.sql` (KevBox repo) — `member_addon`, `member_addon_v`
  (`security_invoker=on`, revoked from anon/authenticated), `default_member_addons()`, seed trigger.
- Debrid runbook: `MEMBER-DEBRID-ONBOARDING.md`.
- Feature background: `plans/done/MEMBER-CONFIG-PLAN.md`.
- Gap analysis + fixes incorporated into these plans: `docs/superpowers/2026-06-09-gap-analysis.md`.
