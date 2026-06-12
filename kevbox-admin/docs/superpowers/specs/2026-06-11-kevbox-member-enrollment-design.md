# Kevbox member enrollment — design spec

**Date:** 2026-06-11 (rev. 2026-06-12 — bind-mount dir not file; key back-fill from Supabase; remote rotate; hardening pass — name preservation, empty-list safety, write perms, locking, rename op, key exposure)
**Status:** Approved design, pending spec review → implementation plan
**Repos touched:** `NuvioTV/kevbox-admin` (primary) + `AIOStreams` (kevbox fork, small change)

## 1. Problem

Adding a member to the kevbox AIOStreams addon today is a manual SSH chore: edit
`KEVBOX_MEMBERS` in `/opt/kevbox/AIOStreams/.env` on persovps, then restart the
container. The admin dashboard (`admin.kevbox.dev`) already manages NuvioTV
members (addons, devices, access) but does **not** touch the AIOStreams allowlist.

Goal: manage AIOStreams membership from the dashboard, user-friendly, with **no
container restart** — an add is live within ~1s.

## 2. Background — current state

- **Dashboard** = Fastify + React SPA monorepo, Supabase Postgres (raw `pg`),
  Supabase-JWT auth with an admin-email allowlist (`ADMIN_EMAILS`). Deployed via
  `deploy.sh` → systemd `kevbox-admin` (port 8787) behind nginx. Auth is already
  solved; **no auth work in this feature.**
- **Members** live in `auth.users` (Supabase-owned), read through the
  owner-privileged view `public.kevbox_auth_users` (id, email, created_at). Each
  member has `member_addon` rows (the TV client's addon list), plus
  `member_access` / `member_device` / `member_device_policy` / telemetry.
- **AIOStreams allowlist** = `KEVBOX_MEMBERS` env (comma-separated, ~261 names)
  on the kevbox container. It gates `…/stremio/k/<name>/<key>/manifest.json`.
  Name rule: `^[a-z0-9._+-]{1,64}$`, normally the email local-part. The kevbox
  fork reads it via `kevboxMembers()` in
  `packages/server/src/utils/kevboxTemplate.ts`. "Empty list" means the **resolved**
  list *after* the file→env fallback (§7) — only a genuinely empty resolved list
  disables kevbox, and a bad template **fails boot** (fail-loud). A present-but-
  empty/malformed `members.json` is treated as "no usable file" and falls back to
  env (§7), so the file alone can never disable the addon.
- **Dead feature:** the old "debrid onboarding" (`DebridForm`, `onboardDebrid`,
  `buildTorrentioUrl`, the Torrentio sort-4 + AIOStreams sort-5 rows, the
  `hasDebrid` badge, `POST /…/debrid`, CLI `onboard-debrid`) was used to add
  Torrentio + AIOStreams addons by hand. The user has moved to a single
  self-hosted addon (the kevbox container) and this onboarding is **no longer
  needed** — this spec removes it.

## 3. Locked decisions

| # | Decision |
|---|----------|
| Access | Add to the existing `kevbox-admin` dashboard (not a new app). |
| Apply model | **Instant, no restart** — dashboard writes a hot-reloaded `members.json`. |
| Member data | **Full registry**: store AIOStreams name + **encrypted** Premiumize key. |
| Shape | **Unify into the member record** — a sidecar enrollment per `auth.users`. |
| Write path | **Shared bind-mounted file** — kevbox reads `members.json` per-request (mtime cache). |
| Legacy 261 | **Keep all** — match each to its `auth.users` row; retain + report any that don't match. |
| On enroll | **Also add the kevbox addon URL** to the member's `member_addon` list. |
| `members.json` | **Lean** — plain JSON array `["name", …]`. |
| Onboarding | **Delete** the old debrid onboarding feature. |

## 4. Data model

`auth.users` is Supabase-owned and cannot be altered, so enrollment lives in a
**sidecar table** keyed by `user_id`:

```sql
create table public.kevbox_member (
  user_id            uuid primary key references auth.users(id) on delete cascade,
  aiostreams_name    text not null
                       check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  premiumize_key_enc text,            -- AES-256-GCM, versioned (§5); NULL allowed (key unknown after backfill miss)
  enrolled           boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
-- Name uniqueness binds ACTIVE members only, so a departed member's name can be
-- reused after un-enroll (enrolled = false). H5.
create unique index kevbox_member_name_active
  on public.kevbox_member (aiostreams_name) where enrolled;
```

Safety net for any legacy allowlist name that does **not** resolve to an
`auth.users` row (expected: none — kept anyway, never dropped):

```sql
create table public.kevbox_allowlist_extra (
  aiostreams_name text primary key
                    check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  note            text,
  created_at      timestamptz not null default now()
);
```

**RLS / exposure lockdown (REQUIRED, C6)**: both tables sit in `public`, so on
Supabase PostgREST auto-exposes them to `anon` / `authenticated` (all 261 members)
— the *same class of leak* as the prior `member_access` view exposure.
`kevbox_member` holds `premiumize_key_enc` ciphertext **and** every member's
allowlist name + `enrolled` flag; `kevbox_allowlist_extra` holds the leftover
names. Prod setup **must** enable RLS on both tables and revoke all grants from
`anon` / `authenticated`. The dashboard is unaffected because the `kevbox_admin`
role is `BYPASSRLS`:

```sql
alter table public.kevbox_member          enable row level security;
alter table public.kevbox_allowlist_extra enable row level security;
revoke all on public.kevbox_member          from anon, authenticated;
revoke all on public.kevbox_allowlist_extra from anon, authenticated;
```

`packages/core/test/schema.sql` gains both tables so core tests run against them.

`aiostreams_name` is **canonical**: it stores the member's *verbatim live allowlist
string* (the exact token already in `KEVBOX_MEMBERS` / their installed `/k/<name>/`
URL), **never a freshly re-derived `localpart(email)`** (C1). Re-deriving at
migration time would rename members whose live name was hand-customized and break
their installed URL at cutover (see §11). New dashboard enrolls may *default* the
name to `localpart(email)`, but the stored value is whatever is confirmed unique and
actually written to the allowlist.

`premiumize_key_enc` is **nullable** on purpose: a member can be in the allowlist
(name only) without a stored key. The key is only needed to *generate/show* the
install URL; the allowlist itself needs only the name.

## 5. Encryption

- **AES-256-GCM** via Node built-in `crypto` (no new dependency).
- 32-byte key from `KEVBOX_ENC_KEY` in `/etc/kevbox-admin/env` (mode 600).
  **Encoding rule** (no ambiguity): exactly 64 hex chars → decoded as hex; else
  decoded as base64; the result must be exactly 32 bytes or the server refuses to
  start. Also refuses to start if the feature is used and the key is missing.
- **Versioned** stored format: `v1.base64(iv).base64(authTag).base64(ciphertext)`
  (12-byte IV). The `v1` prefix lets `KEVBOX_ENC_KEY` be rotated later (decrypt under
  the old key, re-encrypt + bump the tag) without guessing which key produced a
  value (M3).
- **AAD bind**: encrypt with the member's `user_id` as Additional Authenticated Data
  so a ciphertext copied into another member's row fails authentication (defends
  against row-swap).
- New `packages/core/src/crypto.ts`: `encryptSecret(plain, userId, key): string`,
  `decryptSecret(enc, userId, key): string`. The 32-byte `key` is **injected
  explicitly** (not read from env inside the module) so tests can pass a fixed key;
  the server resolves `KEVBOX_ENC_KEY` once at the call site. Both **validate
  structure** (prefix, IV length, tag) and throw a typed error on malformed input —
  never a raw crypto exception into a request path. Decryptable by design (we need
  plaintext to build URLs).
- **Error class mapping (REQUIRED):** distinguish two failure modes.
  - *Structurally-invalid input* (bad `v1` prefix, wrong segment count, bad base64,
    wrong IV/tag length) → the typed malformed-input error → caller maps to **400**.
  - *Authentication failure* (GCM tag mismatch: wrong `KEVBOX_ENC_KEY`, wrong AAD /
    `userId`, or corrupted ciphertext) → a **distinct** typed error → caller maps to
    **500 (internal)**, **never** a client 400. A tag mismatch on well-formed input is
    an operator/data problem, not a client problem. Neither error message may leak
    crypto internals (no key bytes, IV, tag, or "GCM" specifics) — surface a generic
    "decryption failed" to the client and log details server-side.

## 6. `members.json` renderer + write path

**Renderer** (`packages/core/src/kevboxAllowlist.ts`):
- `SELECT aiostreams_name FROM kevbox_member WHERE enrolled = true`
  `UNION SELECT aiostreams_name FROM kevbox_allowlist_extra`, sorted, de-duped,
  regex-validated → `string[]`.
- Serialize to a lean array `["name", …]`.
- **Empty-set safety floor (C2)**: if the rendered set is empty, **refuse to write**
  — log loudly and abort, leaving the previous `members.json` intact. A single bad
  query or a half-applied migration must never be able to blank the allowlist and
  lock out everyone. (Optional stricter guard: also refuse if the new set drops more
  than N% below the previous file's count, behind a `--force` for the deliberate
  mass-removal case.)
- **Atomic write with explicit perms (C4)**: create an in-directory sibling temp file
  (`.tmp-<pid>-members.json`), write, `fchmod 0664`, `fsync`, then `rename` over
  `members.json`. The kevbox container runs as **root** (image
  `gcr.io/distroless/nodejs24-debian12`, no `USER`, no compose `user:`), so it reads
  `members.json` via the **world-read** bit (`0664`) regardless of group — group
  `kevbox` is **not** what lets the container read. The `fchmod 0664` is what keeps the
  file world-readable; if a tight umask were to strip the other-read bit (`0660`/`0600`),
  the root container could still read by ownership but the file would not be group/other
  readable for any non-root writer tooling — so `fchmod 0664` is mandatory. The
  **writer side** is where group matters: `kevbox-admin` must be able to *create* the
  temp file in the shared dir, so the dir is **setgid (`g+s`)** and group `kevbox` so
  new files inherit group `kevbox` regardless of the writer's umask (otherwise a writer
  with the wrong primary group can't create files there). The rename swaps the file's
  inode, which is why the container must bind-mount the **directory**, not the single
  file (see §7).
- **Single-writer serialization (H2)**: every "mutate DB + re-render" op runs under a
  Postgres **advisory lock** (e.g. `pg_advisory_xact_lock(hashtext('kevbox_members'))`)
  held for the whole mutate+render, re-reading the latest DB state *inside* the lock
  before serializing. Without this, two concurrent renders are last-writer-wins and a
  stale snapshot can transiently drop a just-added member.
- Re-render the **full snapshot** after every enroll / un-enroll / rename / rotate /
  migration (simplest and always correct), and **on kevbox-admin boot (H3)** to
  self-heal the file from the DB if a prior render was interrupted.
- **Render-failure is a hard error (H3)**: DB writes commit *before* the file render,
  so a render that throws leaves the DB ahead of the file. The mutating op must
  surface that failure to the caller (API returns 5xx) rather than report success;
  boot-render is the backstop that reconciles it.

**Location & permissions** — a **dedicated shared dir**, not the AIOStreams app
dir, to keep the kevbox-admin sandbox tight:
- File: `/var/lib/kevbox-shared/members.json`, dir + file group `kevbox`, dir mode
  `2775` (**setgid**), file mode `0664` (world-readable — that other-read bit is what
  the root container reads through).
- `kevbox-admin` (systemd user) is in group `kevbox` so it can **create/replace** files
  in the shared dir (writer side). The kevbox container runs as **root**, so it reads
  `members.json` via the world-read bit regardless of group — group membership for the
  container's host user is **not** required for the read.
- systemd `kevbox-admin.service` runs `ProtectSystem=strict`, so add
  `ReadWritePaths=/var/lib/kevbox-shared`.
- Path configurable via `KEVBOX_MEMBERS_FILE` (kevbox-admin side).

## 7. AIOStreams fork change (kevbox container)

Small, additive, backward-compatible:
- `kevboxMembers()` (in `packages/server/src/utils/kevboxTemplate.ts`) gains a
  **file source** with an **mtime+size cache** (mirrors `kevboxTemplate` loading;
  busts on a change to mtime **or** size, so two renders within one mtime-second
  tick aren't missed). Resolution precedence (C3):
  - File **missing**, **0-byte/whitespace**, **malformed JSON**, or a **valid but
    empty `[]`** → treat as "no usable file" and **fall back to `KEVBOX_MEMBERS`
    env** (existing behavior — zero-disruption rollout). Parse errors are caught +
    logged; they never throw into the request path or boot.
  - File parses to a **non-empty array** → use it; validate each name against
    `^[a-z0-9._+-]{1,64}$`, drop+log invalid entries. The reader only ever opens
    `members.json`; the renderer's temp file is an in-directory sibling whose exact
    name is an impl detail (e.g. `.tmp-<pid>-members.json`), so no sibling tmp is ever
    read regardless of name — there is nothing for the reader to explicitly "ignore".
  This makes "empty file" a safe rollback lever (§15), not a kill-switch — only a
  genuinely empty *resolved* list (file fell back to an empty env) disables kevbox.
- `compose.kevbox.yaml`: bind-mount the **directory**
  `/var/lib/kevbox-shared:/app/kevbox-shared:ro` (not the single file) and set
  `KEVBOX_MEMBERS_FILE=/app/kevbox-shared/members.json`. Mounting the dir is
  required: kevbox-admin replaces `members.json` via atomic rename, which swaps the
  inode — a single-file bind-mount would pin the old inode, so the container would
  never see updates and its mtime cache would never invalidate.
- Boot fail-loud check unchanged (still requires a non-empty **resolved** list after
  the precedence above).
- New unit tests for the file source (non-empty file → used; missing/0-byte/`[]`/
  malformed → env fallback; invalid name filtered; mtime **and** size cache
  hit/miss; a sibling temp file present in the dir does not affect the read — the
  reader opens `members.json` by name only).

## 8. Enroll / un-enroll / rename / rotate

`packages/core/src/kevboxMember.ts`. Every mutating op below runs inside the §6
advisory lock and ends with a re-render; a render failure fails the whole op (§6,
H3). Each op first loads the member via `kevbox_auth_users` by `userId` and
**errors if not found**.

- **`enrollMember(db, userId, { aiostreamsName?, premiumizeKey })` → `{ installUrl }`**
  (a dashboard enroll **requires** `premiumizeKey` — it's needed to build both the
  install URL and the `member_addon` row; the only path that creates a name-only,
  keyless `kevbox_member` row is the §11 migration back-fill miss.)
  1. `name = aiostreamsName ?? localPart(member.email)`; validate regex; enforce
     **active-name uniqueness** (the partial index, §4 H5). The stored value is the
     confirmed `name` verbatim — migration passes the live allowlist string here and
     **never** re-derives it (C1).
  2. Encrypt `premiumizeKey` with `userId` as AAD (§5).
  3. Upsert `kevbox_member` (`enrolled = true`).
  4. Build install URL `${KEVBOX_STREAMS_BASE_URL}/stremio/k/${name}/${key}/manifest.json`
     and upsert it into `member_addon` at `KEVBOX_ADDON_SORT = 4`
     (`on conflict (user_id, url) do nothing`).
  5. Re-render `members.json`.
- **`renameMember(db, userId, newName)` (H1)** — the name is in the install-URL path,
  so a rename is **not** cosmetic: validate regex + active-name uniqueness; update
  `kevbox_member.aiostreams_name`; **replace** the kevbox `member_addon` URL (delete
  old `/k/<old>/…`, insert new `/k/<new>/…`) so the device re-syncs; re-render. Like
  rotate it is **breaking mid-flight** — the old name leaves the allowlist before the
  device syncs the new URL. The dashboard therefore **must** expose a *force re-push*
  affordance (§9) for the affected member, and after a rename/rotate the UI **must**
  surface it as a required follow-up for an actively-watching member (not optional
  advice) — this is the install/uninstall push that re-syncs the device immediately.
  A keyless member (no stored key) can't have the URL rebuilt, so
  rename updates the allowlist name only and flags that a key/URL must be re-issued.
- **`unenrollMember(db, userId)`** — set `enrolled = false`, remove the kevbox
  `member_addon` row, re-render. The encrypted key is **retained** (not wiped) so a
  later re-enroll is one click; un-enroll already removes the member from the
  allowlist and the device's addon list, which is the access control. (If least-data
  retention is preferred, null `premiumize_key_enc` here instead — a recorded
  decision, default = retain; §16.)
- **`rotateKey(db, userId, premiumizeKey)`** — re-encrypt (AAD = userId), replace the
  kevbox `member_addon` URL (delete old, insert new). The install URL changes, but
  `member_addon` is the member's **remote** addon list — the TV client picks up the
  new URL on its next config sync, so **no end-user reinstall is needed**. Because the
  old name leaves the allowlist immediately while the device syncs later, the
  dashboard **must** offer the *force re-push* affordance (§9) and surface it as a
  required follow-up for an actively-watching member, mirroring rename. The admin can
  push it immediately via the existing install/uninstall controls on
  `admin.kevbox.dev`. Back-fill (§11) only sets a key when none is stored, so a
  rotate is never clobbered by a migration re-run (H4).

**Interaction with `resetToDefaults` (REQUIRED, R1).** The retained `reset` action
(`actions.ts`, §10) runs `resetToDefaults`, which **deletes every non-default
`member_addon` row** — including the kevbox install URL at `KEVBOX_ADDON_SORT = 4`.
So resetting an **enrolled** member would strip their kevbox addon from the device
while the DB still says `enrolled = true`, desyncing dashboard from device. Required
behavior: resetting an enrolled member **must re-add their kevbox `member_addon` URL**
afterward (rebuild it from the stored name + decrypted key, same as enroll step 4),
**or** `resetToDefaults` must **exclude** kevbox install URLs (`…/stremio/k/…`) from
the rows it deletes. Pick one and implement it so dashboard and device stay in sync; a
keyless enrolled member can't have the URL rebuilt, so the exclude approach is the
safer default. Covered by a test (reset of an enrolled member leaves the kevbox addon
present).

`KEVBOX_STREAMS_BASE_URL` (default `https://streams.kevbox.dev`) is config.

## 9. Dashboard UX

- **`MemberDetail` → new "Kevbox" tab** (`apps/web/src/web/components/KevboxTab.tsx`):
  enrollment status; name (editable, defaults to email local-part); Premiumize key
  (masked, set/rotate — rotate updates the remote `member_addon` URL, picked up on
  the device's next sync, no end-user reinstall); Enrolled toggle; an install URL
  shown **only on an explicit "Reveal / copy URL" action** (not pre-loaded), with a
  Copy button.
  - **Force re-push (REQUIRED affordance):** after a rename or rotate the tab **must**
    show a *Force re-push to device* control (driving the existing install/uninstall
    push that re-syncs the device's `member_addon` immediately) and **must** surface a
    required follow-up prompt for an actively-watching member — because the old name
    leaves the allowlist before the device syncs the new URL (§8). This is a required
    control, not advisory copy.
- **`MemberList`** — replace the `hasDebrid` badge with a **"kevbox" enrolled**
  badge.
- **API** (under the existing admin-guarded `/api`):
  - `GET /api/members/:userId` → include `{ kevbox: { name, enrolled, hasKey } }`
    **only** — never the key-bearing `installUrl` (C5); the default member fetch must
    not carry the secret into browser memory / network logs.
  - `GET /api/members/:userId/kevbox/install-url` → `{ installUrl }` — the *only*
    endpoint that returns the key-bearing URL, served on explicit admin action and
    auditable on its own (§13).
  - `PUT /api/members/:userId/kevbox` `{ name?, premiumizeKey? }` → routes by field
    combination (H1), all validated server-side:
    - not enrolled + `premiumizeKey` (+ optional `name`) → **enroll**.
    - enrolled + `premiumizeKey` only → **rotateKey**.
    - enrolled + changed `name` only → **renameMember**.
    - enrolled + changed `name` + `premiumizeKey` → rename **then** rotate.
    - not enrolled + `name` only (no key) → **reject 400** (enroll requires a key).
  - `DELETE /api/members/:userId/kevbox` → un-enroll.
- `apps/web/src/web/lib/api.ts` gains matching methods; routes in a new
  `apps/web/src/server/routes/kevbox.ts` registered in `app.ts`.

## 10. Delete the old onboarding (cleanup)

Remove (implementation plan verifies each path before deleting):
- `packages/core/src/debrid.ts` (`onboardDebrid`).
- `packages/core/src/defaults.ts` → drop `buildTorrentioUrl`,
  `DEBRID_TORRENTIO_SORT`, `DEBRID_AIOSTREAMS_SORT` (keep unrelated defaults).
- `packages/core/src/index.ts` → drop the dropped exports.
- `apps/web/src/server/routes/actions.ts` → remove `POST /members/:userId/debrid`
  (keep `reset`).
- `apps/web/src/web/components/DebridForm.tsx` → delete; remove its use +
  `onOnboardDebrid` wiring from `App.tsx` / `MemberDetail.tsx`.
- `apps/web/src/web/lib/api.ts` → remove `onboardDebrid`.
- `packages/cli` → remove the `onboard-debrid` action + its `format` output.
- `types.ts` `MemberSummary.hasDebrid` → replace with `enrolled` (kevbox); update
  the `listMembers()` query accordingly.
- Remove/replace debrid-specific tests; mark old debrid docs historical.
- **Sequencing (M4):** the §11 back-fill URL parser is **new, independent** code in
  the migration — it must not reuse any debrid helper deleted here. Land deletion and
  migration together so the migration never imports a removed module.

## 11. Migrate the 261

One-off, idempotent, re-runnable script (`packages/cli` subcommand or
`packages/core` migration). It **iterates the `KEVBOX_MEMBERS` names and preserves
each verbatim** — the live allowlist string is canonical and is what gets stored,
never a re-derived `localpart(email)` (C1).

0. **Source of truth (M1):** the exact 261-name `KEVBOX_MEMBERS` string is read from
   a value handed to the migration explicitly — a CLI arg or a one-shot env var
   copied from the AIOStreams `.env`, recorded in the run log — not guessed. The
   whole migration trusts this input, so capture it, don't infer it.
1. Parse the list → canonical set. **Store the name VERBATIM**; lower-case **only**
   for the comparison/de-dupe key (the stored allowlist string keeps its original
   case/form — see C1). Trim, de-dupe on the lowercased key, regex-validate. A token
   that **fails the name regex** (`^[a-z0-9._+-]{1,64}$`) is a hard blocker: **report
   it and BLOCK `--apply`** — silently dropping it would remove that member from the
   allowlist. Do not merely "report and continue".
2. **Resolve each name → `user_id`**, strongest key first:
   a. **Authoritative:** a `member_addon` row whose URL is
      `…/stremio/k/<name>/<key>/manifest.json` with `<name>` == this name — ties the
      live allowlist token directly to a `user_id` (and yields the key for step 3 in
      one shot). When matching this URL with a SQL `LIKE` pattern, **escape `LIKE`
      metacharacters** in `<name>` — names legitimately contain `_` (and could
      contain `%`), both of which are `LIKE` wildcards; build the pattern with the
      metachars escaped (e.g. an `ESCAPE` clause) so `a_b` does not also match `axb`.
   b. **Fallback:** `auth.users` where `lower(split_part(email,'@',1)) = name`.
   On resolve → **insert-only** upsert storing `aiostreams_name = <this verbatim
   name>`, `on conflict (user_id) do nothing` so a re-run **never** flips an admin's
   `enrolled = false` back to true (H4).
   **Conflict handling (REQUIRED — must populate a `conflicts` list IN CODE, not just
   prose):** if one name resolves to **multiple** users (ambiguous), **or** multiple
   names resolve to the **same** `user_id` (duplicate), the script **must** append a
   structured entry to a `conflicts` collection and **SKIP** that resolution — never
   guess. Conflicts are printed in the dry-run report and, like a loss/rename,
   **BLOCK `--apply`** (see step 5) until resolved.
3. **Back-fill the key from Supabase** — only when `premiumize_key_enc IS NULL` (never
   overwrite an admin-rotated key on re-run, H4). The key is the `<key>` segment of
   the member's kevbox `member_addon` URL,
   `…/stremio/k/<name>/<key>/manifest.json`. If **multiple** rows match, prefer the
   `sort = 4` row, else the most-recently-updated; **verify the URL's `<name>` equals
   the stored name** before trusting its key (H6, and the C1 cross-check). Encrypt
   (AAD = userId) + store. No matching row → leave NULL (member keeps working via
   their installed URL; the dashboard just can't regenerate/rotate until a key is
   set).
4. Unmatched names → insert into `kevbox_allowlist_extra` (verbatim) + **print a
   report** (expected empty per the "they all have Supabase entries" assumption; if
   non-empty, those members still work — they're in the allowlist — they're just
   unmanaged in the dashboard).
5. **Dry-run by default + in-code apply gate**: render the candidate `members.json`
   set **in memory** and, **in code**, compute the `lost` / `renamed` / `added` deltas
   against the original `KEVBOX_MEMBERS` set (compare on the lowercased key, report the
   verbatim names). Print the diff **and** the `conflicts` list (step 2) and any
   malformed-token blockers (step 1). `--apply` is **hard-blocked in code** if there is
   **any** loss, **any** rename, **any** malformed-token blocker, or **any** conflict —
   the script exits non-zero without writing. Only a clean dry-run (zero lost, zero
   renamed, zero malformed, zero conflict) lets `--apply` write rows + the file. This
   is an assertion in the script, **not** a manual shell `diff` the operator eyeballs.
   The empty-set safety floor (§6 C2) still applies on the real write.

## 12. Config / secrets summary

| Var | Side | Purpose |
|-----|------|---------|
| `KEVBOX_ENC_KEY` | kevbox-admin | 32-byte AES key for Premiumize keys |
| `KEVBOX_MEMBERS_FILE` | both | path to `members.json` (write / read) |
| `KEVBOX_STREAMS_BASE_URL` | kevbox-admin | install-URL base (`https://streams.kevbox.dev`) |
| `KEVBOX_MEMBERS` | AIOStreams | retained as fallback/seed during rollout |
| `KEVBOX_ADDON_SORT` | kevbox-admin | sort slot for the kevbox `member_addon` row (default `4`) |

The §11 migration reads `KEVBOX_MEMBERS` as an **explicit one-shot input** (CLI arg
or copied env), distinct from the AIOStreams runtime use of the same var (M1).

## 13. Security

- Premiumize keys encrypted at rest (AES-256-GCM, versioned, AAD = `user_id`; key in
  600 env file, §5).
- **RLS lockdown (REQUIRED, C6):** `kevbox_member` (ciphertext + every member's
  allowlist name + `enrolled` flag) and `kevbox_allowlist_extra` are `public` tables,
  so on Supabase PostgREST would auto-expose them to `anon` / `authenticated` — the
  same class as the prior `member_access` view-leak. Prod setup **must** `enable row
  level security` on both and `revoke all ... from anon, authenticated` (DDL in §4);
  `kevbox_admin` is `BYPASSRLS` so the dashboard still reads/writes. This is a setup
  gate, not optional hardening.
- `members.json` holds **names only** (no secrets); group-restricted `0664` in a
  setgid dir so every atomic write stays group-readable (§6).
- Install URLs embed the key → returned **only** by the dedicated
  `…/kevbox/install-url` endpoint on explicit admin action, **never** in the default
  member fetch and **never logged** (C5). Ensure the Fastify logger doesn't log that
  route's response body or the `premiumizeKey` request field.
- **Audit (REQUIRED — built + tested this feature, not a follow-up):** write a
  `kevbox_audit` row per enroll / rename / rotate / un-enroll **and** per install-URL
  reveal, capturing `admin email + user_id + action + timestamp` and **no secret
  values** (no key, no install URL). This must be implemented and covered by tests in
  this feature — it is **not** deferred to a later pass, because this surface mutates
  261 people's access and their keys.
- **Logger redaction (REQUIRED — built + tested):** the Fastify logger must redact the
  `premiumizeKey` request field and any install-URL value so neither appears in logs;
  this redaction must be implemented and **tested** (assert a log line for the
  install-url route / a `premiumizeKey` body shows the redacted placeholder, never the
  raw value). Not deferred.
- Atomic file writes; no shared write to the AIOStreams app dir (dedicated
  `/var/lib/kevbox-shared`).
- All new `/api/...kevbox` routes inherit the existing `requireAdmin` pre-handler.

## 14. Testing

- **core:** crypto round-trip incl. **wrong-AAD/tamper rejection**, malformed-input
  rejection, `v1` parse; renderer (union/sort/validate/atomic) **+ empty-set
  refuses-to-write (C2) + perms/setgid + advisory-lock serialization**;
  enroll/**rename**/unenroll/rotate; name validation + **active-only uniqueness +
  name-reuse after un-enroll (H5)**; migration **verbatim-name preservation (C1) with
  lowercase-only compare key, member_addon-URL resolution + email fallback,
  `LIKE`-metachar escaping for names containing `_`/`%`, insert-only re-run keeps
  `enrolled=false` and rotated keys (H4), multi-row tie-break + name cross-check
  (H6), conflicts list populated + apply blocked on ambiguous/duplicate resolution,
  malformed-token blocks `--apply`, in-code loss/rename delta gate blocks `--apply`,
  dry-run set-equality assert** (against `schema.sql`).
- **server:** `PUT /kevbox` field-combination routing (enroll/rename/rotate/reject),
  `DELETE /kevbox`, and that `GET member` omits the key while
  `…/kevbox/install-url` returns it (auth, validation, side-effects);
  **audit row written per enroll/rename/rotate/unenroll + per install-url reveal with
  no secret values; logger redaction hides `premiumizeKey` + install-url in log
  output**; malformed-input crypto error → 400 while an authentication/tag-mismatch
  crypto error → 500 with a non-leaky message (S3).
- **AIOStreams fork:** file source — non-empty used; missing/0-byte/`[]`/malformed →
  env fallback; invalid name filtered; mtime **and** size cache hit/miss; a sibling
  temp file in the dir does not affect the read (reader opens `members.json` by name);
  boot fail-loud only on empty *resolved* list.
- **integration:** enroll → `members.json` written (world-readable `0664`) → file
  contents correct; render-failure surfaces as op failure; boot-render reconciles a
  stale file; **resetToDefaults on an enrolled member keeps the kevbox `member_addon`
  URL present (re-added, or excluded from the reset delete — R1)**.

## 15. Rollout

0. **Provision the shared dir BEFORE any `docker compose up` (ordering, S8a):** create
   `/var/lib/kevbox-shared` with owner `kevbox-admin`, group `kevbox`, mode `2775`
   (**setgid**) **first**. If the first `docker compose up` (step 1) runs against a
   missing path, Docker auto-creates the bind-mount source as `root:root 0755`, and the
   `kevbox-admin` writer then can't create `members.json` in it. Add systemd
   `ReadWritePaths=/var/lib/kevbox-shared`.
1. AIOStreams fork: add file source + compose mount + deploy (file absent → env
   fallback → zero disruption). Safe only because step 0 already created the dir.
2. **Apply `kevbox_member_setup.sql` as the `postgres` superuser in the Supabase SQL
   editor (ordering, S8b)** — it `CREATE TABLE`s both tables, the partial unique index,
   the RLS `enable`/`revoke` (§4 C6), and any `GRANT` to `kevbox_admin`. The least-priv
   `kevbox_admin` role in `SUPABASE_DB_URL` **cannot** `CREATE TABLE` or `GRANT`, so
   the schema step must not run under that role.
3. **Add `KEVBOX_ENC_KEY` + `KEVBOX_MEMBERS_FILE` to `/etc/kevbox-admin/env` BEFORE
   deploying the new bundle (ordering, S8c)** — `loadConfig` requires them and the
   server **won't boot** without them (§5 refuses to start when the key is missing).
   Then deploy kevbox-admin: renderer (safety floor + advisory lock + boot-render),
   enroll/rename/rotate API/UI, delete onboarding.
4. Run the 261 migration **dry-run first** (§11 step 5); review the diff (expect zero
   lost, zero renamed, zero malformed, zero conflict — `--apply` is hard-blocked
   otherwise); then `--apply`. Verify `members.json` set == `KEVBOX_MEMBERS` set and
   the file is `0664` (world-readable).
5. Confirm kevbox is serving from the file; keep `KEVBOX_MEMBERS` as a backup, remove
   later once confident.
6. **Rollback (M2):** because the file source wins when present, env can't override a
   bad file — to roll back, **delete (or empty) `members.json`**, which makes the
   container fall back to `KEVBOX_MEMBERS` env (§7); redeploy not required.

## 16. Risks / open items

- Back-fill recovers keys from the `<key>` segment of each member's kevbox
  `member_addon` URL in Supabase; members without that row remain name-only
  (acceptable — they keep working via their installed URL).
- Permissions are asymmetric and easy to over-think: only the **writer**
  (`kevbox-admin`) needs group `kevbox` + the setgid dir to create files; the **reader**
  is the root container and reads through the world-read (`0664`) bit, so no reader
  group setup is needed. The setgid dir + `fchmod 0664` on write are the guardrails
  (§6); validate on the box that a freshly rendered file is `0664` and that the
  container actually serves from it.
- Cross-repo change: AIOStreams fork edit lands as its own commit/deploy and must
  precede the kevbox-admin cutover (step 1 before step 4).
- **Open decision:** retain vs. wipe `premiumize_key_enc` on un-enroll (§8 default =
  retain).
- **`resetToDefaults` interaction (R1, §8):** reset deletes the kevbox `member_addon`
  URL at sort 4; resetting an enrolled member must re-add it (or reset must exclude
  kevbox URLs) so dashboard and device stay in sync — a required behavior, not a
  nice-to-have.
- **Validate at dry-run (deferred):** confirm every enrolled member's kevbox
  `member_addon` URL matches `…/stremio/k/<name>/<key>/manifest.json` exactly (no
  legacy host/query-string variants) — both back-fill and rotate key off that shape;
  the §11 step-5 dry-run diff is where this surfaces.
