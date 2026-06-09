# KevBox Admin — Spec & Plan Gap Analysis

- **Date:** 2026-06-09
- **Scope:** `specs/2026-06-09-kevbox-admin-design.md` + the 4 plans (scaffold-and-core, cli, web-admin, deploy)
- **Method:** 7 finder lenses (traceability, cross-plan contracts, data-model, security, debrid/defaults, deploy/ops/testing, completeness) → adversarial verification of every candidate against the full doc set + ground-truth repo artifacts (`member_addon_setup.sql`, `MEMBER-DEBRID-ONBOARDING.md`, Kotlin defaults). 64 candidates → **29 confirmed** (35 refuted as covered-elsewhere or wrong) → deduped to **16 distinct gaps**.
- **Status: RESOLVED (2026-06-09).** All 16 gaps below have been fixed in the spec + 4 plans. Headline fixes: the web server now builds as a single self-contained **esbuild bundle** (`dist/server.js`, `@kevbox-admin/core` inlined) so the deploy ships just `dist/` with no `npm ci` (C1+C2); the DB cred is a **least-privileged `kevbox_admin` role** via the IPv4 **Session pooler** host (H1+M3); `resetToDefaults` is a single atomic statement (M2); web routes 404 on unknown member (M1); `/healthz` + error sanitizer + nginx rate-limit/headers (L1/L2/M5); snapshot-before-bulk shipped in v1 (M4); URL validation, responsive layout, debrid drift-guard test, e2e isolation, and stale cross-refs all addressed. This section is retained as the audit trail; the surrounding docs are now the source of truth.

## Executive summary

The plans are unusually thorough and internally well-cross-referenced, and the data-layer reasoning (single source of truth for defaults, RLS/view bypass via direct Postgres, idempotent upserts) is sound. **But the build/deploy contract between Plan 3 (web) and Plan 4 (deploy) was never reconciled, and as written the very first deploy cannot succeed** — two independent, deterministic, deploy-blocking bugs. There is also one genuinely uncomfortable security property (the server holds project-superuser DB creds on the most internet-exposed box) and a v1 safety sequencing inversion (destructive all-member bulk ops ship before the snapshot/undo that protects them). The rest are medium/low robustness, hardening, and ops-runbook omissions appropriate to a single-operator family tool.

---

## CRITICAL — must fix before the first deploy

### C1. Server entrypoint path mismatch: deploy expects `dist/server.js`, web build emits `dist/server/index.js`
- **Where:** deploy.md L20/L140 (`SERVER_ENTRY="apps/web/dist/server.js"`, guarded by `[[ -f "$SERVER_ENTRY" ]] || err`) and L265 (systemd `ExecStart=.../current/dist/server.js`) **vs** web-admin.md L92 (`build:server: tsc -p tsconfig.json`), L130 (`outDir: dist/server`), L93/2231/2236 (`node dist/server/index.js`).
- **Impact:** `tsc` emits a multi-file `dist/server/` tree, not a flat `dist/server.js`. Under `set -euo pipefail`, deploy.sh aborts at the existence check on the **first** `./deploy.sh` before any scp. Even if forced past, the systemd unit's `ExecStart` points at a file that doesn't exist, so the service never starts. The deploy plan's prereq claim of "a bundled Node server entrypoint at apps/web/dist/server.js" is fictional — there is no bundler step anywhere in Plan 3.
- **Fix:** Pick one topology and make all three references agree. Simplest: repoint `SERVER_ENTRY` and the systemd `ExecStart` to `dist/server/index.js` (the `../public` static resolution already assumes that location). Alternatively add a real bundler (`esbuild --bundle`) to Plan 3 that emits `dist/server.js` *and* inlines `@kevbox-admin/core` (which also fixes C2). Make the Task 6 `ss -ltnp | grep 8787` check actually gate success.

### C2. The `@kevbox-admin/core` workspace dependency cannot be installed in the deploy staging dir
- **Where:** deploy.md L150-159 — stages only `apps/web/dist` + `apps/web/package.json` + the **root** `package-lock.json` into a fresh temp dir, then `cd "$STAGE" && npm ci --omit=dev … || npm install --omit=dev`. `apps/web` declares `"@kevbox-admin/core": "*"` (web-admin.md L99) and the server imports real values from it at runtime (L503-634).
- **Impact:** `core` is a `private`, never-published workspace package. In the isolated stage: `npm ci` fails the lockfile-in-sync check (root multi-workspace lockfile ≠ a lone `@kevbox-admin/web` package.json); the `npm install` fallback tries to resolve `@kevbox-admin/core@*` from the public registry → 404. Best case the artifact ships **without** `core`, and `node dist/server/index.js` throws `ERR_MODULE_NOT_FOUND` on startup → `Restart=on-failure` crash-loop. The "self-contained production-only node_modules" claim is false for the one intra-repo dependency. (This is *separate* from C1; even with the path fixed, the runtime import still fails.)
- **Fix:** Either (a) bundle the server so `core` is inlined (no runtime resolution), or (b) before staging, `npm pack` / copy `packages/core/dist` into the stage and rewrite the dep to `file:./core` (or vendor `node_modules/@kevbox-admin/core`), or (c) run a workspace-aware `npm ci` from the repo root and ship the resulting `apps/web` + hoisted `node_modules`. Don't `2>/dev/null`-mask the install step — a silent stage failure tars a broken artifact.

> C1 and C2 are the same underlying defect class: **Plan 3 and Plan 4 describe two different build topologies.** Reconcile them in one editing pass; a single decision (bundle vs. ship-node_modules) closes both.

---

## HIGH

### H1. The server holds **project-superuser** DB credentials on the most internet-exposed component
- **Where:** deploy.md env.example L300 (`SUPABASE_DB_URL=postgres://postgres:…@db…`); spec §4.1; web-admin.md `createPool`.
- **Impact:** The Fastify process on `admin.kevbox.dev` has god-mode over the **entire** Supabase project — all schemas (`auth`, `storage`, `public`, anything else sharing the project), can read `auth.users` password hashes, and can `DROP` anything. If that public Node process is compromised (a dependency CVE, SSRF, RCE), the blast radius is the whole project, not just `member_addon`. This is the single worst property of the design: maximum privilege on the most-exposed surface.
- **Fix:** Create a least-privileged Postgres role for the admin scoped to `select/insert/update/delete` on `public.member_addon`, `execute` on `default_member_addons()`, and `select(id,email,created_at)` on `auth.users` (or a SECURITY DEFINER wrapper that returns only those). Put *that* role's connection string in `SUPABASE_DB_URL`. The admin keeps its direct-Postgres power (RLS/view bypass) without carrying superuser.

---

## MEDIUM

### M1. Web routes pass `:userId` straight to core with no member-existence check — breaks the "one shared core, two correlated front-ends" error contract
- **Where:** web-admin.md addons.ts `POST /members/:userId/addons` (L533-543), `/reset` (L606-609), `/debrid` (L612-625), `/addons/order` (L573-583). CLI resolves the member first via `requireMember`/`getMember` and prints a clean "not found" (cli.md L649-656).
- **Impact:** Same bad input → CLI returns 404-style "not found", web returns a raw **500** (FK violation) on add/debrid or a misleading **200** on reset/reorder. The two front-ends diverge on the member-not-found contract the spec markets as correlated, and no spec line defines the intended response.
- **Fix:** Add a member-existence guard in `core` (or a shared `requireMember`) so both front-ends get a typed `MemberNotFound` → 404 in web, "not found" in CLI.

### M2. `resetToDefaults` is a non-atomic delete-then-insert — a crash between the two leaves a member with **zero** addons
- **Where:** scaffold-and-core.md Task 7 reset.ts L726-733 (two separate `db.query` calls, no `BEGIN/COMMIT`); production `db` is a `pg.Pool` (autocommit per query). The seed trigger fires only on `auth.users` INSERT (member_addon_setup.sql L65-67), so a `member_addon` delete never re-seeds.
- **Impact:** Process death / connection drop / transient insert failure after the delete commits wipes the member's addon list (including debrid rows) with no auto-recovery — and it mirrors to that member's TVs on next open. Tests miss it because they run inside `withRollback` on a single checked-out client, masking the Pool path.
- **Fix:** Check out one client and wrap delete+insert in `BEGIN/COMMIT` (one-liner). Apply the same pattern anywhere else that does multi-statement mutation.

### M3. Direct Postgres `db.<ref>.supabase.co:5432` is **IPv6-only** on current Supabase — persovps likely can't connect
- **Where:** deploy.md env.example L300, web-admin.md L156, spec §4.1/§5 all hardcode the direct host. No doc mentions the Supavisor pooler, IPv4 add-on, port 6543, or IPv6 egress.
- **Impact:** On an IPv4-only VPS (common), the server starts fine (pool connects lazily) and the deploy smoke check passes (it curls the static SPA), but **every** authenticated `/api/*` call 500s on connect. The troubleshooting note blames "a bad `SUPABASE_DB_URL` value," masking the real cause.
- **Fix:** Use the pooler host (`<ref>.pooler.supabase.com`, 6543 transaction mode — and disable prepared statements / use a pooler-safe pg config) or the paid IPv4 add-on; document the choice and add an IPv6/egress note to the troubleshooting list.

### M4. Destructive all-member bulk ops ship in v1, but the snapshot/undo safety net is deferred to v1.1 — sequencing inversion
- **Where:** spec §3 L37 (bulk ops are v1 scope) vs §4.6 L130 / §8 L159 (snapshot deferred to v1.1); Plan 1 Task 9 bulk.ts; no backup step in Plan 4.
- **Impact:** `bulkSwapUrl` mutates rows across **every** member with no export, no snapshot, and no device-side undo. A fat-fingered `fromUrl`/`toUrl` can corrupt the whole family's addon lists at once; the "type CONFIRM" modal stops accidental clicks, not wrong inputs. The only recovery is re-onboarding each member's debrid by hand.
- **Fix:** Pull the v1.1 "snapshot member_addon to JSON before a bulk op" forward into v1, gating every bulk op. Cheap insurance for the one irreversible operation.

### M5. No rate-limiting, security headers, or explicit CORS decision on the internet-exposed admin + login
- **Where:** web-admin.md `buildApp` (Fastify created with only `{ logger: false }`); deploy.md Task 4 nginx conf (no `limit_req`, no security headers).
- **Impact:** Nothing app-side throttles `/api/*` (each call triggers a Supabase `getUser`) or Supabase login attempts against the known admin email (credential stuffing). No CSP/HSTS/X-Frame-Options/X-Content-Type-Options. CORS is undefined-by-omission rather than by decision.
- **Fix:** Add `@fastify/rate-limit` (tight on the auth-adjacent paths), set security headers (`@fastify/helmet` or nginx `add_header`), and explicitly declare the CORS posture (same-origin only).

---

## LOW (robustness / ops-runbook / polish)

- **L1 — Observability:** no `GET /healthz` doing `select 1`; `logger: false`; the deploy smoke check curls `/` (static SPA) so it returns 200 even when the DB is unreachable. Add a DB-touching health route and curl *that*; enable request logging to journald.
- **L2 — Fastify error leakage:** no `setErrorHandler`; core throws raw, web surfaces `j.error` to the SPA → stack traces / pg detail (and, with a superuser URL, infra hints) can leak. Add a sanitizing error handler.
- **L3 — No audit log** of destructive actions (bulk add/swap, reset, delete). Acceptable for single-op, but a compromise or fat-finger is undiagnosable after the fact. Consider a tiny append-only `admin_audit` table.
- **L4 — No secrets-rotation runbook** for `SUPABASE_DB_URL`, the anon key, or `ADMIN_EMAILS`. Note the non-obvious part: the anon key is **baked into the SPA at build time** (`VITE_SUPABASE_ANON_KEY`), so rotating it needs a rebuild + redeploy, not just an env edit.
- **L5 — No concurrency / lost-update protection:** the table has `updated_at` but every write ignores it; web+CLI or two tabs silently clobber. Single-operator scope mitigates. Either add an `updated_at`-precondition (→ 409) or document "last write wins" as an accepted limitation.
- **L6 — URL validation is non-empty-only:** an operator can save `htps://typo`; it ships to the TV with no feedback. Add a `new URL()` + `http(s)` check in core; optionally a non-blocking manifest-reachability warning in the SPA.
- **L7 — `hasDebrid` brittleness:** exact-string `NOT IN default_member_addons()` flips a universal addon to "has debrid" if a default URL drifts (e.g. the token refresh the runbook says *will* happen). Cosmetic only (overview badge gates nothing). Tolerable; note it or compare on a normalized key.
- **L8 — No responsive layout** though the spec stresses phone use: `.app` is a fixed `320px 1fr` grid at `100vh` with no `@media`. "Reachable from a phone" (network) is met; "operable on a phone screen" is not. Add a single-column breakpoint under ~700px.
- **L9 — Debrid template drift risk:** `core` `buildTorrentioUrl` and `MEMBER-DEBRID-ONBOARDING.md` are hand-kept in sync with only a comment to enforce it. Strings match today; consider a unit test asserting the generated URL equals the runbook's documented shape.
- **L10 — e2e smoke test runs against live prod + is unplanned:** spec §7 mutates the real KevBox project (creates/deletes a real `auth.users` row, firing the real trigger + cascade) with no isolation/transaction; an abort mid-run orphans an `auth.users` row, and inserting into `auth.users` directly may violate Supabase auth invariants (missing `auth.identities`/`encrypted_password`). No plan task actually implements it. Either give it teardown-guaranteed isolation or run it against a throwaway project.
- **L11 — No CI:** all tests need a manually-started docker Postgres and the e2e hits prod, so nothing gates a commit/PR — risky for a fork that periodically merges upstream NuvioTV.
- **L12 — Stale cross-references:** spec §6/§9 (and a MemberDetail.tsx placeholder string) point to `MEMBER-CONFIG-PLAN.md` / `MEMBER-ACCESS-PLAN.md` at repo root, but they moved to `plans/done/` and `plans/`. Doc-only friction; repoint them.

---

## Strengths (what's solid — don't "fix" these)

- **Single source of truth for defaults** is correctly preserved: `resetToDefaults` re-seeds from `default_member_addons()` rather than hardcoding a 4th copy, and the verification confirmed the Kotlin/SQL default sets are consistent.
- **RLS/grant reasoning is correct:** direct Postgres (vs the locked-down `member_addon_v` view / anon-authenticated REST) is the right call for the email join + bulk ops, and the verifier confirmed no core query mistakenly targets the hardened view.
- **Idempotency** via `unique(user_id,url)` upserts and the bulk-op confirm gates are appropriate.
- **The plans cross-reference each other** and even self-flag the build-output risk (deploy.md L680) — the contradiction is acknowledged, just not actually fixed.
- The adversarial pass **refuted 35 of 64** candidate gaps (e.g. JWT verification via `getUser`, cascade-delete behavior, the defaults-drift premise) as already-correct or already-covered — the design holds up under scrutiny on most axes.

## Recommended pre-build fix checklist

1. **[C1+C2] Reconcile the build/deploy contract** — one decision (bundle the server, or ship a workspace-aware `node_modules`), then make `SERVER_ENTRY` + systemd `ExecStart` + the `npm` staging step all consistent. Nothing deploys until this is fixed.
2. **[H1] Replace the `postgres` superuser URL with a least-privileged scoped role** before exposing the box.
3. **[M2] Wrap `resetToDefaults` (and any multi-statement mutation) in a transaction.**
4. **[M3] Switch to the pooler/IPv4-reachable DB host** (or confirm persovps IPv6 egress) and document it.
5. **[M4] Pull the pre-bulk snapshot into v1** so the destructive op ships with its safety net.
6. **[M1] Add a shared member-existence guard** so web and CLI agree on not-found.
7. **[M5] Add rate-limiting + security headers** on the public admin.
8. Sweep the LOW list — `/healthz`, error sanitizer, responsive breakpoint, URL `new URL()` check, stale cross-refs — most are one-liners worth doing in the same pass.
