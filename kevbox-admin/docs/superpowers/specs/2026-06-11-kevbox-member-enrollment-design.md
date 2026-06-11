# Kevbox member enrollment — design spec

**Date:** 2026-06-11
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
  `packages/server/src/utils/kevboxTemplate.ts`; an empty list disables kevbox
  and a bad template **fails boot** (fail-loud).
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
  aiostreams_name    text not null unique
                       check (aiostreams_name ~ '^[a-z0-9._+-]{1,64}$'),
  premiumize_key_enc text,            -- AES-256-GCM; NULL allowed (key unknown after backfill miss)
  enrolled           boolean not null default true,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
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

`packages/core/test/schema.sql` gains both tables so core tests run against them.

`premiumize_key_enc` is **nullable** on purpose: a member can be in the allowlist
(name only) without a stored key. The key is only needed to *generate/show* the
install URL; the allowlist itself needs only the name.

## 5. Encryption

- **AES-256-GCM** via Node built-in `crypto` (no new dependency).
- 32-byte key from `KEVBOX_ENC_KEY` in `/etc/kevbox-admin/env` (mode 600), hex or
  base64. Server refuses to start if the feature is used and the key is missing.
- Stored format: `base64(iv).base64(authTag).base64(ciphertext)` (12-byte IV).
- New `packages/core/src/crypto.ts`: `encryptSecret(plain): string`,
  `decryptSecret(enc): string`. Decryptable (we need plaintext to build URLs).

## 6. `members.json` renderer + write path

**Renderer** (`packages/core/src/kevboxAllowlist.ts`):
- `SELECT aiostreams_name FROM kevbox_member WHERE enrolled = true`
  `UNION SELECT aiostreams_name FROM kevbox_allowlist_extra`, sorted, de-duped,
  regex-validated → `string[]`.
- Serialize to a lean array `["name", …]`.
- **Atomic write**: write `members.json.tmp` in the same dir, `fsync`, `rename`
  over `members.json` (so kevbox never reads a half-written file).
- Re-render the **full snapshot** after every enroll / un-enroll / rename /
  migration (simplest and always correct).

**Location & permissions** — a **dedicated shared dir**, not the AIOStreams app
dir, to keep the kevbox-admin sandbox tight:
- File: `/var/lib/kevbox-shared/members.json`, group `kevbox`, mode `0664`.
- `kevbox-admin` (systemd user) and `kevin` (kevbox container's host user) both in
  group `kevbox`.
- systemd `kevbox-admin.service` runs `ProtectSystem=strict`, so add
  `ReadWritePaths=/var/lib/kevbox-shared`.
- Path configurable via `KEVBOX_MEMBERS_FILE` (kevbox-admin side).

## 7. AIOStreams fork change (kevbox container)

Small, additive, backward-compatible:
- `kevboxMembers()` (in `packages/server/src/utils/kevboxTemplate.ts`) gains a
  **file source**: if `KEVBOX_MEMBERS_FILE` exists, read + JSON-parse it with an
  **mtime cache** (mirrors `kevboxTemplate` loading), validate each name against
  `^[a-z0-9._+-]{1,64}$`, drop+log invalid entries. If the file is absent/empty,
  **fall back to `KEVBOX_MEMBERS` env** (existing behavior — zero-disruption
  rollout).
- `compose.kevbox.yaml`: bind-mount
  `/var/lib/kevbox-shared/members.json:/app/members.json:ro` and set
  `KEVBOX_MEMBERS_FILE=/app/members.json`.
- Boot fail-loud check unchanged (still requires a non-empty resolved list).
- New unit tests for the file source (file present → used; absent → env fallback;
  invalid name filtered; mtime cache hit/miss).

## 8. Enroll / un-enroll / rotate

`packages/core/src/kevboxMember.ts`:

- **`enrollMember(db, userId, { aiostreamsName?, premiumizeKey })` → `{ installUrl }`**
  (a dashboard enroll **requires** `premiumizeKey` — it's needed to build both the
  install URL and the `member_addon` row; the only path that creates a name-only,
  keyless `kevbox_member` row is the §11 migration back-fill miss.)
  1. `name = aiostreamsName ?? localPart(member.email)`; validate regex; enforce
     uniqueness.
  2. Encrypt `premiumizeKey`.
  3. Upsert `kevbox_member` (`enrolled = true`).
  4. Build install URL `${KEVBOX_STREAMS_BASE_URL}/stremio/k/${name}/${key}/manifest.json`
     and upsert it into `member_addon` at `KEVBOX_ADDON_SORT = 4`
     (`on conflict (user_id, url) do nothing`).
  5. Re-render `members.json`.
- **`unenrollMember(db, userId)`** — set `enrolled = false`, remove the kevbox
  `member_addon` row, re-render.
- **`rotateKey(db, userId, premiumizeKey)`** — re-encrypt, replace the kevbox
  `member_addon` URL (delete old, insert new). ⚠️ The install URL changes, so the
  member must **reinstall** — surface this clearly in the UI.

`KEVBOX_STREAMS_BASE_URL` (default `https://streams.kevbox.dev`) is config.

## 9. Dashboard UX

- **`MemberDetail` → new "Kevbox" tab** (`apps/web/src/web/components/KevboxTab.tsx`):
  enrollment status; name (editable, defaults to email local-part); Premiumize key
  (masked, set/rotate, with the reinstall warning on rotate); Enrolled toggle;
  generated install URL with a Copy button.
- **`MemberList`** — replace the `hasDebrid` badge with a **"kevbox" enrolled**
  badge.
- **API** (under the existing admin-guarded `/api`):
  - `GET /api/members/:userId` → include `{ kevbox: { name, enrolled, hasKey, installUrl } }`.
  - `PUT /api/members/:userId/kevbox` `{ name?, premiumizeKey? }` → enroll/update.
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

## 11. Migrate the 261

One-off, idempotent, re-runnable script (`packages/cli` subcommand or
`packages/core` migration):
1. Read the current `KEVBOX_MEMBERS` list (261 names).
2. For each name, find `auth.users` where `lower(split_part(email,'@',1)) = name`
   (fallback: exact email match). On match → upsert `kevbox_member`
   (`enrolled = true`).
3. **Back-fill the key**: parse it out of the member's existing `member_addon`
   URLs (`premiumize=<key>` in the Torrentio URL, or `/k/<name>/<key>/` in an
   AIOStreams URL). If found → encrypt + store; else leave `premiumize_key_enc`
   NULL (the member keeps working via their already-installed URL; the dashboard
   just can't regenerate it until a key is set).
4. Unmatched names → insert into `kevbox_allowlist_extra` + **print a report**
   (expected empty per the "they all have Supabase entries" assumption).
5. Render `members.json` and assert its set equals the original `KEVBOX_MEMBERS`
   set (no one lost) before cutover.

## 12. Config / secrets summary

| Var | Side | Purpose |
|-----|------|---------|
| `KEVBOX_ENC_KEY` | kevbox-admin | 32-byte AES key for Premiumize keys |
| `KEVBOX_MEMBERS_FILE` | both | path to `members.json` (write / read) |
| `KEVBOX_STREAMS_BASE_URL` | kevbox-admin | install-URL base (`https://streams.kevbox.dev`) |
| `KEVBOX_MEMBERS` | AIOStreams | retained as fallback/seed during rollout |

## 13. Security

- Premiumize keys encrypted at rest (AES-256-GCM; key in 600 env file).
- `members.json` holds **names only** (no secrets); still group-restricted `0664`.
- Install URLs embed the key → shown only to authed admins, **never logged**.
- Atomic file writes; no shared write to the AIOStreams app dir (dedicated
  `/var/lib/kevbox-shared`).
- All new `/api/...kevbox` routes inherit the existing `requireAdmin` pre-handler.

## 14. Testing

- **core:** crypto round-trip; renderer (union/sort/validate/atomic);
  enroll/unenroll/rotate; name derivation+validation; migration matching +
  key back-fill (against `schema.sql`).
- **server:** `PUT`/`DELETE /kevbox` (auth, validation, side-effects).
- **AIOStreams fork:** file source (present/absent/invalid/mtime).
- **integration:** enroll → `members.json` written → file contents correct.

## 15. Rollout

1. AIOStreams fork: add file source + compose mount + deploy (file absent → env
   fallback → zero disruption).
2. Create `/var/lib/kevbox-shared` + group `kevbox` + perms; add systemd
   `ReadWritePaths`.
3. kevbox-admin: schema migration, `KEVBOX_ENC_KEY`, renderer, enroll API/UI,
   delete onboarding; deploy.
4. Run the 261 migration; verify `members.json` set == `KEVBOX_MEMBERS` set.
5. Confirm kevbox is serving from the file; keep `KEVBOX_MEMBERS` as a backup,
   remove later once confident.

## 16. Risks / open items

- Back-fill can only recover keys for members whose `member_addon` URLs still
  embed them; others remain name-only (acceptable — they keep working).
- Group/sandbox permissions between two service users is the fiddliest infra
  step; validate in staging or carefully on the box.
- Cross-repo change: AIOStreams fork edit lands as its own commit/deploy and must
  precede the kevbox-admin cutover (step 1 before step 4).
