# Stremio → KevBox Direct Import (Trakt-free) — Design Spec

**Date:** 2026-06-14 (**rev 3** — same day; revised after a multi-agent gap analysis verified every load-bearing claim against ground truth)
**Status:** Design approved → ready for implementation **planning**, **BUT** two merge-safety blockers (§9) and one write-path preflight (§7.3) must clear before the canary commit.
**Depends on:** [`2026-06-14-cloud-restore-design.md`](./2026-06-14-cloud-restore-design.md) — **now DEPLOYED to live prod** (`scmqdptagksltnwiveyh`) with the canary allowlist gate. Its §4/§5 RPC contract, §8 gating, and the `get_sync_owner` **canary gate** are all load-bearing here. (Note: "proven end-to-end on the operator's TV" is **pending** per operator memory — on-device E2E is a gating step, not done; §13.)
**Code lives in:** `~/projects/trakt-stremio-import` (a patched fork of `aliyss/trakt-stremio-import`).

> **rev 3 — what changed and why.** A 26-agent gap analysis read the actual SQL / Kotlin / TS and adversarially re-checked each finding. The mechanical contract (§5 RPC names/arg-order, `_for(owner, profile_id, payload)`, `progress_key` format, ms/epoch-ms units, the canary gate, client gating) is **CONFIRMED** — implementation-ready. The danger is concentrated in §9. Changes:
> 1. **§9 merge corrected — rev 2's central safety claim was wrong for two of three subsystems.** `watch_progress` restore IS non-destructive (the rev-2 trace holds). But **`watched_items` and `library` client restore are wholesale REPLACE, not a union**, and their local-preservation is gated behind `lastSuccessfulPushMs > 0` — which is exactly `0` for the never-synced backfill cohort. So importing silently wipes a member's KevBox-only watched items / saved titles on first pull, reported as success. This is a **hard blocker** with required fixes (§9). The active-17 merge-sensitive members are precisely the exposed cohort.
> 2. **Converter corrections (§4, §6).** The watched-bitfield decode cannot be "reused" verbatim (it is async/network-coupled and Trakt-shaped); the season loop must NOT be bounded by `parseInt(state.watched.split(':')[1])` (NaN for non-IMDB id namespaces → silently drops a whole show); `Date.parse('')` → NaN needs the legacy guard ported; series `progress_key`/`video_id` need a defined fallback; the `timeOffset>0` gate silently drops finished titles from continue-watching.
> 3. **Write-path EXECUTE is unprovable from the repo (§7.3).** The "postgres can EXECUTE the seven `_for` functions" claim rests on live DB ownership the SQL doesn't capture → a **one-query preflight is now a hard go/no-go gate** before building.
> 4. **Vault stores encrypted email+password, not just the authKey (§7.1).** Over the ~10-month tail authKeys go stale and the importer has no unattended re-login path with authKey-only.
> 5. **Rollout hardening (§7.1, §10, §12, §16).** Monitoring/alerting on failed imports; a cohort-drift re-snapshot sweep at poller launch; exclude Trakt-connected members (`karimassad`, `alecco`) from the canary; treat a 0-item library for a known-active account as a failure, not a success.
>
> Full finding-by-finding ledger with file:line evidence in **Appendix A**.

> **rev 2 — what changed and why.** rev 1 assumed an operator-run CLI at member *onboarding*, with an **ungated** `get_sync_owner()` and "no per-member flag." Three things changed since:
> 1. **The canary gate shipped.** `get_sync_owner()` is now CLOSED BY DEFAULT — it returns a member's owner id only if they are in `public.sync_canary_members`, else `NULL`. The importer writes through the same `sync_push_*` RPCs, so **an import silently no-ops for any non-allowlisted member.** The gate is now the per-member rollout control rev 1 said didn't exist.
> 2. **Migration is gradual (~10 months), and existing members have no future onboarding hook.** The ~330 members are already provisioned; their real switch point is **first KevBox login**, not onboarding. So the trigger is first-login, not onboarding.
> 3. **The merge against existing local history was traced in the client** (`WatchProgressPreferences.mergeRemoteEntries`) — it is non-destructive and recency-based for **watch_progress**. (rev 3: this is true for watch_progress only; watched_items/library are NOT — see §9.)
>
> rev 2 kept the verified RPC contract (§5) and field mapping (§6) from rev 1; rev 3 keeps §5 and corrects the converter details in §6.

## 1. Goal

Backfill a KevBox member's existing **Stremio** watch history, continue-watching, and saved library
**directly into the KevBox Supabase project** via the same `sync_push_*` RPCs the Android client uses —
**no Trakt in the loop**. After import, the member's device restores it through the (now-deployed)
cloud-restore feature.

Two delivery paths, same import core:
- **Automatic (C1)** — for the ~320 members who have **not yet** logged into KevBox: their import fires
  **on first login** (detected server-side via the `claim_device` signal) with **no operator action**.
- **Manual backfill** — for the **17 members already logged in** (whom the first-login trigger would
  miss because their first login is in the past): a one-time operator-run import. This cohort doubles as
  the on-device canary for the whole pipeline.

**Non-goals:** the server schema (delivered + deployed by cloud-restore); collections/profiles/addons/plugins
(no Stremio source); multi-profile (`profile_id = 1`).
**~~Any Android client changes (none)~~ — rev 3:** the no-client-changes goal is in tension with the §9
merge-safety blocker. Folding in the fix is a planning decision: either an **operational sequencing**
workaround (no client change) or a **scoped client merge patch** (revises this non-goal). See §9.

## 2. Background — why this works without Trakt

The Trakt detour existed only because KevBox had no server-side restore schema; the device could get
cross-device history *only* via Trakt scrobbling (forward-only, never backfills). Cloud-restore now
deploys the `sync_*` RPCs, so the device restores watch progress / watched items / library straight from
KevBox Supabase. The cloud-restore **§8 client gating** makes a non-Trakt member exactly the case where
the Supabase path is live (CONFIRMED against the client — see Appendix A):

| Subsystem | Runs for a non-Trakt member? |
|---|---|
| watch_progress, watched_items | **Yes** (`shouldUseSupabaseWatchProgressSync()` true — the `hasEffectiveTraktConnection && source==TRAKT` AND collapses to false when Trakt is not connected; `WatchProgressSyncService.kt:87-91`) |
| library | **Yes** (skipped only when `librarySourceMode == TRAKT` **AND** Trakt-authenticated; `StartupSyncService.kt:384-390`) |

So injecting Stremio history into a member's Supabase rows is enough for it to flow down on next login.
No Trakt account. (rev 3: "no client edits" — see §9; the *gating* needs none, but the *merge safety* may.)

## 3. The canary gate interaction (load-bearing)

`get_sync_owner()` resolves the owner for **every** public sync RPC via `nullif(get_sync_owner(),'')::uuid`.
It returns the caller's uid **only if** they are in `public.sync_canary_members`, else `NULL`
(CONFIRMED: table + function in `get_sync_owner_setup.sql:24-40`). Consequences for the importer:

- **Public-wrapper writes require allowlisting.** A member JWT calling `sync_push_*` while not allowlisted
  resolves to a `NULL` owner → R4 no-op → **zero rows written, no error.** The verify step (`sync_pull_*`)
  is *also* owner-gated, so it reads back 0 and looks "successfully empty." (We do **not** use this path —
  see §7.3 — but the gate still governs restore.)
- **The admin `_for` write is gate-INDEPENDENT** (CONFIRMED: `_for` bodies take an explicit `p_owner` and
  never call `get_sync_owner`; they no-op only when `p_owner IS NULL`). So our chosen write path lands rows
  regardless of allowlist status. **This changes the §12 gate-closed detection** (see §12): we cannot infer
  "not allowlisted" from a push no-op, because the `_for` push always succeeds — we must check
  `sync_canary_members` membership directly.
- **Restore ALWAYS requires allowlisting.** Even after a successful `_for` import, the member's device only
  pulls the seeded data once they are allowlisted. So **enabling a member = allowlist + import.**
- **The gate IS the rollout control.** Enable members one at a time (allowlist + import). The allowlist
  remains the permanent kill-switch (`truncate public.sync_canary_members` disables all sync instantly;
  delete one row to disable one member). We do **not** ungate to `select auth.uid()::text` during the
  10-month rollout — we accumulate allowlisted members instead.

## 4. Architecture & components

Built as new entrypoints in the existing fork, reusing its proven Stremio-reading half. Trakt code stays
in place but becomes legacy (§14).

| Component | File | Responsibility | New? |
|---|---|---|---|
| Stremio reader | `src/utils/stremio.ts` | login (email+pass → authKey), `getLibrary`, `getCinemetaMeta` | reuse |
| Converter | `src/utils/convert-kevbox.ts` | Stremio library → `{watchProgress[], watchedItems[], library[]}`. **Pure, network-free, unit-testable — the Cinemeta episode list is INJECTED as an argument, not fetched inside** (rev 3: this is the only way §4's purity claim and §6.2's watched-decode coexist; see §6.2). | **new** |
| KevBox client | `src/utils/kevbox.ts` | member-owner resolution, batched `sync_push_*_for` writes over the admin connection, verify via base-table reads, Cinemeta fetch for the converter, gate/allowlist check | **new** |
| CLI entrypoint | `src/kevbox-import.ts` | orchestrate fetch → convert → (dry-run \| commit) → verify. Used for the **manual backfill** of the 17 and for ad-hoc re-runs. | **new** |
| Auto runner (C1) | `src/kevbox-poller.ts` (+ `scripts/kevbox_poller.sh`) | the C1 poller: scan for newly-active members with stored creds + `pending` status → allowlist + import + verify + mark `done`/`failed`; journal + dead-letter + failure alerts. | **new** |

The converter is the only correctness-bearing pure unit and **performs no network I/O** (all Cinemeta data
is passed in); `kevbox.ts` owns all KevBox + Cinemeta I/O; the CLI and the poller are two thin orchestrators
over the same core. No new npm deps (`axios`, `stremio-watched-bitfield` already present).

## 5. Verified client contract (CONFIRMED against ground truth in rev 3)

All param names/order, payload keys, and return shapes were verified against the live client call sites
**and the deployed SQL function bodies**. PostgREST binds by argument **name** for the public wrappers;
the `_for` path binds **positionally** over the admin DB connection.

**Push RPCs (public wrappers — NOT used by the importer; here for reference):**

| RPC | Args | Payload item shape |
|---|---|---|
| `sync_push_watch_progress` | `p_entries jsonb`, `p_profile_id int` | `{content_id, content_type, video_id, season?, episode?, position, duration, last_watched, progress_key}` |
| `sync_push_watched_items` | `p_items jsonb`, `p_profile_id int` | `{content_id, content_type, title, season, episode, watched_at}` |
| `sync_push_library` | `p_items jsonb`, `p_profile_id int` | `{content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating?, genres[], addon_base_url, added_at}` |

**Admin `_for` writes (the importer calls THESE — §7.3):** `sync_push_watch_progress_for(p_owner uuid, p_profile_id int, p_entries jsonb)`, `sync_push_watched_items_for(p_owner, p_profile_id, p_items)`, `sync_push_library_for(p_owner, p_profile_id, p_items)`. **⚠️ `p_profile_id` is SECOND** (CONFIRMED `watch_progress_setup.sql:63-64`, `watched_items_setup.sql:57-58`, `library_setup.sql:35-36`) — unlike the public wrappers. Call positionally as `_for(owner, 1, payload)`.

**Pull RPCs (owner-gated; used for read-back only where the member is allowlisted):** `sync_pull_watch_progress`, `sync_pull_watched_items(p_profile_id, p_page, p_page_size)`, `sync_pull_library(p_profile_id, p_limit, p_offset)`. For verifying a not-yet-allowlisted member, read the **base tables** over the admin connection instead (the pulls would return empty via the gate — §13).

**Verified invariants:**
- `progress_key` = `content_id` for movies; `` `${content_id}_s${season}e${episode}` `` for episodes (`_sNeM`, not colon-delimited). **CONFIRMED byte-for-byte against the client** (`WatchProgressPreferences.kt:479-485`).
- `position`/`duration` are **milliseconds**; Stremio's `state.timeOffset`/`state.duration` are also ms — no conversion (server columns `bigint`).
- `content_type` is `"movie"`/`"series"` — identical to Stremio's `type` (no server CHECK constraint, so the converter is the only guard — assert it).
- `last_watched`/`watched_at` are **epoch-ms `int8` NOT NULL**; Stremio's `state.lastWatched` is ISO → `Date.parse(...)`. **rev 3 guard:** `Date.parse('')`/`Date.parse(undefined)` → `NaN`; a NaN must never reach a payload — **drop the row** for watch_progress (no fallback, per §9 recency rule) and **fallback** for watched_items/library (see §6).
- `profile_id` = **1** fleet-wide.
- **season/episode encoding is cosmetic** (rev 3 correction): both functions parse `nullif(e->>'season','')::int`, so an **omitted** key and an **explicit JSON null** both store NULL identically. The only hard requirement is *never send an empty string or `0`*. Tests should assert "resolves to NULL," not a specific JSON encoding.

## 6. Field mapping (rev 3 — corrected)

Source: `StremioLibraryObject`/`StremioLibraryObjectState`. Iterate the library, skip `removed`.

**Content-id namespace:** usually IMDB ids (`tt…`). Non-standard ids (`kitsu:…`, addon-specific) are stored
fine server-side (no format CHECK) but **surface as unresolved/blank tiles on-device** (the meta addon can't
resolve them) — a degraded tile, not data loss. The converter logs the **actual non-`tt`/`tmdb:`/`trakt:`
ids** (not just a count) to the per-member archive so the operator can audit blank tiles. (rev 3: non-tt
series also break the watched-bitfield decode — see §6.2.)

### 6.1 watch_progress — one entry per title
Emit when `state.timeOffset > 0 && state.duration > 0` and not the junk case (`position ≤ 1 && duration ≤ 1`):
- common: `content_id=_id`, `content_type=type`, `position=state.timeOffset`, `duration=state.duration`, `last_watched=Date.parse(state.lastWatched)` — **drop the entry if this is `NaN` or `≤ 0`** (the §9 protection key is `lastWatched > 0`; a 0/NaN entry would be un-protected on the client merge).
- movie: `video_id = state.video_id || _id`, `progress_key = _id`, season/episode omitted.
- series: `season/episode = state.season/episode`; `progress_key = ${_id}_s${season}e${episode}`. **rev 3 fallbacks:** (a) `video_id = state.video_id || \`${_id}:${season}:${episode}\`` — never emit `undefined`; (b) **if season or episode is missing/0, SKIP the entry** (or derive S/E from `state.video_id` = `tt..:S:E` before keying) — never emit `progress_key = _id` for a series, which would collide with the bare series-level slot and corrupt the continue-watching row.

> **rev 3 limitation (document, not a bug):** the `timeOffset > 0` gate means continue-watching is backfilled
> ONLY for titles **mid-play at export time**. Stremio clears `timeOffset` on completion, so finished titles
> produce **no** watch_progress row — they flow only via watched_items (§6.2). §13 verification compares the
> Stremio **source** count, not just the echoed server count, so this under-import is visible.

### 6.2 watched_items — every watched movie + episode
- movie: emit iff `state.flaggedWatched === 1` → `{content_id:_id, content_type:"movie", title:name, season:null, episode:null, watched_at}`
- series: decode `state.watched` via `stremio-watched-bitfield`. **rev 3 — NOT a verbatim reuse of `convert.ts:116-137`** (that function is `async` + fetches Cinemeta, and returns Trakt-shaped season objects). Instead:
  1. `kevbox.ts` fetches the Cinemeta meta and builds `episodeList` (id`:`season`:`number triples, cf. `convert.ts:102-114`) and the bitfield `wb` (`watchedBitfield.constructAndResize`, `convert.ts:129-135`), then **passes them into the converter** (keeps it network-free).
  2. The converter **iterates the Cinemeta `episodeList` directly** and emits a row for every `wb.getVideo(\`${_id}:${season}:${number}\`)` that is truthy → `{content_id:_id, content_type:"series", title:name, season, episode:number, watched_at}`.
  3. **Do NOT bound the season loop by `parseInt(state.watched.split(':')[1])`** (the legacy `convert.ts:161-167` pattern) — for non-numeric id namespaces (`kitsu:…`, `mal:…`) that is `NaN`, the loop runs zero times, and **the entire show's watched episodes are silently dropped.** Iterating `episodeList` avoids this entirely.
- `watched_at` = parsed `state.lastWatched`, fallback `Date.now()`. (Fallback OK here: the merge is a union and `watched_at` is benign metadata; the server `where excluded.watched_at > wi.watched_at` guard prevents a stale value regressing a fresher one and never un-watches.)

### 6.3 library — saved titles *(toggleable, default on)*
Filter `!removed && !temp`: `{content_id:_id, content_type:type, name, poster, background, release_info:year, added_at:Date.parse(_ctime), poster_shape:"POSTER", genres:[], addon_base_url:"", description:"", imdb_rating:null}`. genres/rating/addon_base_url have no Stremio source (nullable/defaulted per cloud-restore §5.3; library defaults CONFIRMED to satisfy every column constraint). **rev 3:** `added_at = Date.parse(_ctime)` can be `NaN` if `_ctime` is missing → **coalesce to `0`** (the column default) before emitting (mirror the legacy guard `convert.ts:19-26`); `poster_shape` must be the literal string `"POSTER"` (do not lowercase). Behind `--no-library`. **⚠️ library restore is REPLACE-not-union on the device — see §9; for any member with pre-import local library, the import must seed a superset or run with `--no-library`.**

## 7. Rollout model — C1 auto-on-login + manual backfill of the 17

### 7.1 Automatic path (C1) — the ~320 future switchers
**Trigger = `claim_device`.** When a member logs into KevBox, the one-device access control claims their
device (a `member_device` row appears). `claim_device`/access resolve `auth.uid()` directly and do **not**
consult the sync gate (CONFIRMED `member_device_setup.sql:52-82`), so a member can log in + claim a device
while gated-off for sync — a reliable server-side first-use signal that needs **no client change.**

**rev 3 trigger caveats:**
- The poller candidate query keys off the **existence** of a `member_device` row (has-ever-claimed) + stored
  creds + `status='pending'` — not a freshly-written row (a re-login of an existing device only `UPDATE`s
  `last_seen`, `member_device_setup.sql:66-69`).
- **Capped-out edge:** a genuinely *new* device denied by the one-device cap writes **no** row
  (`member_device_setup.sql:71-76` returns false before insert). For the C1 cohort (never-logged-in) this
  never bites — their first claim always inserts. But a member who must re-claim on a replacement TV while
  capped produces no signal; fall back to a login-derived signal (`auth.users.last_sign_in_at`) or manual
  backfill for those.
- **Cohort drift:** the poller is built last (§17). Anyone who first-logs-in during the manual phase is in
  neither the manual-17 nor a running poller → add a **re-snapshot sweep at poller launch** (§16) to absorb
  them.

**Runner = persovps poller** (`kevbox-poller.ts`, cron). Each tick:
1. Find members with stored Stremio creds + import `status = pending` + a `member_device` row.
2. For each: **allowlist** them (`insert into sync_canary_members`, committed) → run the import core
   (fetch → convert → `_for` write → verify) → mark `status = done`, or `failed` **with the error and an
   alert** for retry (bounded; dead-letter after N attempts — §16).

**Why a poller, not a login webhook (C2) or in-Deno (C3):** C1 reuses the existing TS importer verbatim,
never touches the login path or client, and is trivially retryable. The few-minutes lag is absorbed by
KevBox re-pulling on every app start. C2/C3 are later upgrades.

**Credential vault.** A secured table `member_stremio_creds(user_id uuid pk, stremio_email text,
stremio_password_enc bytea, stremio_authkey text, status text default 'pending', attempts int default 0,
last_run timestamptz, last_error text)`, RLS on, **no grants to anon/authenticated** (read only by the admin
poller). **rev 3: store the encrypted email+password, not just the authKey** — authKeys go stale over the
~10-month tail and the importer has **no unattended re-login path** with authKey-only (`getLibrary`'s single
`loginWithToken` retry needs a still-valid token and falls back only when creds are supplied,
`stremio.ts:35-61,83-89`). With email+pass vaulted, the poller re-logins via `updateAuthKeyWithCredentials`.
Encrypt with the existing `KEVBOX_ENC_KEY` (pgcrypto vs app-side is a §19 mechanics decision). The operator
holds all members' Stremio creds.

### 7.2 Manual backfill — the 17 already-active members
Their first login is in the past, so C1's trigger never fires for them. Each gets a one-time
**allowlist + `kevbox-import.ts --commit`** run. This is also the **canary cohort** (§10). The CLI and the
poller share the same import core, so the manual runs validate the exact code C1 will later automate.

### 7.3 Writes-as-member: DECIDED — admin `_for` (option b) — **preflight required**
The runner writes owner-scoped rows by calling the inner `sync_push_*_for(p_owner, …)` functions directly
over the **prod admin DB connection** the poller already holds (for allowlist + vault). The inner functions
take an explicit owner and enforce all R2/R3 correctness — the importer shapes payloads only.

**⚠️ rev 3 — the EXECUTE claim is NOT provable from the repo; preflight before building.** The `_for`
functions are `revoke all … from public, anon, authenticated` (`watch_progress_setup.sql:108`,
`watched_items_setup.sql:93`, `library_setup.sql:73`) with **no** `GRANT EXECUTE` and **no** `ALTER … OWNER
TO` anywhere. They are therefore callable **only by the function owner**. The design works *iff* the poller's
connection role IS that owner. The pooler string `postgres.<ref>` strongly suggests `postgres` owns them, but
ownership is live-DB state the committed SQL does not capture, and the deployed probe only exercises the
public wrappers. **Hard go/no-go before any build:**
```sql
select p.proname, r.rolname as owner,
       has_function_privilege(current_user, p.oid, 'EXECUTE') as can_exec
from pg_proc p join pg_roles r on r.oid = p.proowner
where p.proname like 'sync_push_%_for';
-- run on the ACTUAL poller connection; expect owner = connecting role and can_exec = true for all.
```
If the poller connects as a non-owner role, every `_for` write fails permission-denied and the entire write
path is dead — add an explicit `GRANT EXECUTE … TO <poller_role>` migration.

**Two distinct credentials.** **#1 Stremio** email+pass → authKey (READ the source; the operator has all of
them; `POST api.strem.io/api/login`, `stremio.ts:12-33`). **#2 KevBox** member password → member JWT (WRITE
as the member). **Option (b) needs only #1** — it writes via the admin connection with an explicit owner, so
**no KevBox member passwords (#2) are ever stored or used.** Rejected: (a) member-JWT-via-password-grant
(would store ~330 KevBox passwords) and (c) minted-JWT (would hold the project JWT secret). Both the manual
CLI and the C1 poller use (b) — a single write path.

The `_for` write is **gate-independent** (explicit owner), so import can run before allowlisting; but
**restore still requires the member allowlisted**, so enabling a member is still allowlist + import.

## 8. Auth & write path

- **Stremio:** email+pass from the vault → authKey → `api.strem.io` (re-login on stale token).
- **KevBox writes:** per §7.3 (admin `_for`). The `_for` functions enforce all R1–R10 correctness
  server-side — the importer carries **zero** correctness logic, it only shapes payloads.
- `SUPABASE_URL`/anon key from `~/.config/stremio-kevbox-migration/app.env`. The poller additionally holds
  the prod admin DB connection (for vault + allowlist + option (b) writes + base-table verify). **No secret
  hardcoded;** creds touch only `api.strem.io` and KevBox endpoints.

## 9. Merge semantics — **rev 3: watch_progress safe; watched_items + library are a BLOCKER**

The import only **seeds the cloud**; the device does pull → merge-into-local → push on its next start. The
import never runs on the device. The gap analysis traced all three client merge paths:

### 9.1 watch_progress — VERIFIED non-destructive (rev-2 claim holds)
**`WatchProgressPreferences.mergeRemoteEntries`** (`:328-385`, called with `removeMissingRemoteEntries=true`):
- **No local loss for never-synced members.** The remove-missing step (`:350-365`) preserves any local entry
  with `lastWatched > lastSuccessfulPushMs`. A gated-off member has **never pushed → `lastSuccessfulPushMs = 0`**,
  so *every* real local entry (`lastWatched > 0`) is protected. **Caveat:** the key is `lastWatched > 0`, so
  the converter MUST drop entries whose parsed `lastWatched` is `0`/`NaN` (§6.1) — load-bearing.
- **Conflicts resolve by recency** (`:367-378`): remote (Stremio) overwrites local only if `remote.lastWatched >
  local.lastWatched`. Server re-guards on the next push (`watch_progress_setup.sql:82-83`). Order-independent.

### 9.2 watched_items — **CONTRADICTED: REPLACE, not union → silent loss (CRITICAL)**
rev 2 asserted "union, never un-watches." The code does the opposite for the import case. The first-ever pull
(`StartupSyncService` cold path) calls `replaceWithRemoteItems` (`WatchedItemsSyncService.kt:244-249` →
`WatchedItemsPreferences.kt:222-261`). It builds the new set from **remote only**, and preserves local items
**only inside `if (lastSuccessfulPushMs > 0L)`** (`:242`). A never-synced member has `lastSuccessfulPushMs = 0`
→ the preserve block is **skipped** → local is fully overwritten by the imported set (`:255`). The additive
`mergeRemoteItems` (`:170`) exists but is **never called** on this path. **Net: any KevBox-only watched item
not present in the member's Stremio import is silently deleted on first restore — reported as success.**

### 9.3 library — **CONTRADICTED: REPLACE, not union (HIGH; recoverable)**
`StartupSyncService.kt:390-392` → `LibraryPreferences.mergeRemoteItems` (`:109-124`) rebuilds local from the
remote snapshot only; the sole guard is "remote empty → keep local" (`:112-114`), which the seeding defeats.
No `lastSuccessfulPushMs` protection. So a local-only saved KevBox title not in the Stremio import is dropped
on the next pull. Less severe than 9.2 only because the **cloud** keeps the union (server push is upsert with
no delete, `library_setup.sql:33,56`), so a later pull re-hydrates — but on-device it is lost in between.

### 9.4 Required fix (decide in planning — gates the canary)
The exposed cohort is exactly the **never-synced backfill members** (the active-17, especially the 6
merge-sensitive). Two viable fixes:

- **(A) Operational sequencing — no client change.** Per merge-sensitive member: **allowlist → have them open
  the app once** (the device pushes its existing watched_items + library to cloud, setting
  `lastSuccessfulPushMs > 0`; the empty-remote pull is harmless) → **then run the import** (additive on top via
  `_for`) → next app start unions correctly. A per-member two-touch sequence; fine for ~6 members; the C1
  ~320 are clean (no local history) so unaffected.
- **(B) Scoped client patch — robust, revises the no-client-changes non-goal.** Make `replaceWithRemoteItems`
  and `LibraryPreferences.mergeRemoteItems` **union when `lastSuccessfulPushMs == 0`** (the exact guard
  `watch_progress` already has). Removes the sequencing fragility fleet-wide. Requires a client release.

**Recommendation:** (A) for the canary now (no release needed), and adopt (B) before fleet-wide C1 if any
non-trivial fraction of the ~320 turn out to have pre-existing local history. Either way, the §10 canary
on-device check **must explicitly verify watched_items + library survival**, not just continue-watching.

## 10. Already-active backfill cohort (the 17)

**Definition:** members with a `member_device` claim = logged into KevBox at least once = the cohort C1's
first-login trigger will miss. **Regenerate the snapshot any time** (columns CONFIRMED to exist) with:

```sql
select u.email, d.user_id,
       coalesce(round(a.watch_seconds/3600.0,1),0) as watch_hrs,
       to_char(d.first_seen,'YYYY-MM-DD') as first_login, to_char(d.last_seen,'YYYY-MM-DD') as last_seen,
       case when coalesce(a.watch_seconds,0) > 0 then 'MERGE-SENSITIVE' else 'clean' end as class,
       (cm.user_id is not null) as allowlisted
from (select user_id, min(first_seen) first_seen, max(last_seen) last_seen
      from public.member_device group by user_id) d
join auth.users u on u.id = d.user_id
left join (select user_id, sum(watch_seconds) watch_seconds from public.member_activity_daily group by user_id) a on a.user_id=d.user_id
left join public.sync_canary_members cm on cm.user_id=d.user_id
order by coalesce(a.watch_seconds,0) desc, d.last_seen desc;
```

**Snapshot (2026-06-14): 17 members** — treat as a **point-in-time** value, re-run at execution time rather
than hardcoding. The concrete list (emails + auth UIDs) is kept **out of this committed spec** (PII); it
lives in the gitignored `~/.config/stremio-kevbox-migration/backfill-cohort-2026-06-14.md`.

**Merge-risk classifier = `watch_seconds > 0`:**
- **MERGE-SENSITIVE (6 of 17)** — real local KevBox history → the merge path that must hold (§9). Ideal canary
  targets (real history to protect).
- **clean (10 of 17)** — logged in, never watched → no local history → import is a plain restore.
- (Row 1 is the operator — already the cloud-restore canary, already allowlisted.) Backfill cohort = the other 16.

**⚠️ rev 3 — exclude Trakt-connected members from the canary.** `accounts/` shows `alecco` and `karimassad`
were Trakt-migrated (live `trakt_accesstoken`), and `karimassad` is in the active-17. While Trakt is
connected, §2 gating keeps `shouldUseSupabaseWatchProgressSync()` **false**, so the import will **not** restore
→ a misleading canary that reads back as "didn't work." Either exclude them, or make **Trakt-disconnect a hard
prerequisite step before their backfill** (and verify the flag flips, `TraktAuthDataStore.clearAuth()` →
`:39-40,92,165-180`).

**Canary-first sequencing:**
1. **Preflight (§7.3 EXECUTE query) — go/no-go.**
2. Build the import core + CLI (§4); resolve the §9.4 merge fix (A or B).
3. **Allowlist + `--dry-run` then `--commit`** ONE merge-sensitive member (most-watched, **not** Trakt-connected) →
   verify on the real TV: continue-watching/history/**watched-marks**/**saved library** restored, **no lost
   local history (watch_progress, watched_items AND library), no regressed positions.**
4. Backfill the remaining 15 (the other 5 merge-sensitive, then the 10 clean).
5. **Only then** build/enable the C1 poller for the ~320 future switchers.

## 11. Operator workflow, dry-run & idempotency

- **Manual (CLI):** `kevbox_import.sh <stremio_email pass | authkey> <member_uid|kevbox_email>` — reads
  `SUPABASE_URL`/anon from `~/.config/stremio-kevbox-migration/app.env`. **Defaults to `--dry-run`** (prints
  the three payload arrays + counts + the Stremio source counts, pushes nothing). Eyeball counts → re-run
  `--commit`. Then report verify counts (server read = ground truth).
- **Auto (poller):** no per-member operator action; dry-run internally as a shape check, then commit, verify,
  mark `done`/`failed`.
- **Idempotent & safe to re-run:** server R2 `last_watched`/`watched_at` guards + R3 unique keys mean
  re-imports never duplicate or regress (CONFIRMED `watch_progress_setup.sql:78-83`, `watched_items_setup.sql:69-72`).
  Top-ups (member watched more in Stremio later) = run again.
- Archive each member's run to `accounts/<member>.json` (incl. the non-tt id list, §6).

## 12. Error handling

- **RPC not deployed:** `404`/`PGRST202` → fail-fast "cloud-restore schema not deployed" → exit non-zero.
- **`_for` EXECUTE denied (new):** permission-denied on the first `_for` call → fail-fast "poller role lacks
  EXECUTE on `_for` (run §7.3 preflight / add GRANT)" → exit non-zero. (Loud, not silent.)
- **Member not allowlisted (new, gate-aware):** because the `_for` write is gate-independent, do **not** infer
  this from a push no-op. Check membership explicitly **before verify**:
  `select exists(select 1 from public.sync_canary_members where user_id = :owner)`. The runner allowlists
  (committed) before import, so this should only fire on an ordering bug — but **never report a not-allowlisted
  member as a successful import**, since restore will silently no-op.
- **Stale Stremio token / no re-login material (new):** if the vault has only an authKey and it is expired,
  `getLibrary` throws → fail-fast "stale Stremio token, no re-login material" + alert. (Mitigated by vaulting
  email+pass, §7.1.)
- **0-item library for a known-active account (new):** `datastoreGet` returning `[]` is a *successful* empty —
  do NOT mark `done`. If the member is known-active (has watch_seconds), treat 0 items as `failed` + alert.
- **Auth failure:** distinguish Stremio login vs KevBox auth failure.
- **Cinemeta lookup blip:** failed episode-list fetch logs a warning, skips that show's *episodes*
  (continue-watching + movie rows still go); re-run later to fill.
- **Batch failure:** abort with batch offset/size + PostgREST body; already-pushed batches are safe.
- **Poller hard-failure:** bounded retries via `attempts`; after N, dead-letter to a distinct status + alert
  (don't silently leave `pending` forever or loop).
- No foreground `sleep` (sandbox-blocked); in-process pauses only.

## 13. Verification

After `--commit`, read back the three subsystems (via `sync_pull_*` if the member is allowlisted, else the
**base tables** over the admin connection) and report counts. **Also compare against the Stremio SOURCE
counts** — the converter legitimately drops some rows (finished titles §6.1, Cinemeta blips), so source-vs-dest
reconciliation is the only way under-import is visible; server counts alone would just echo the converter's own
output. **Server read = ground truth — never claim success from the push log.** A `--verify-only` mode runs
just this. For MERGE-SENSITIVE members, verification is **on-device** (the TV): confirm existing KevBox
continue-watching, **watched-marks, and saved library all survived** and nothing regressed (§10 step 3).
**On-device E2E of the cloud-restore canary is still pending per operator memory — it gates fleet-wide.**

## 14. Trakt deprecation & the existing Trakt users

- **New members:** pure Stremio → KevBox; Trakt never touched.
- **Existing Trakt users (`alecco`, `karimassad`):** run the importer (Stremio → Supabase), then **disconnect
  Trakt on their device** — while Trakt is the progress source, the Supabase sync is client-gated *off* (§2);
  disconnecting flips `shouldUseSupabaseWatchProgressSync()` true so the imported data restores (CONFIRMED).
  Documented as a one-time manual step. **Sequence the disconnect BEFORE their backfill verification** (§10)
  or their canary reads as a false negative. `karimassad` is in the active-17 → an A/B check **only after**
  Trakt is disconnected.
- The Trakt tool (`convert.ts`, `trakt.ts`, `sync.ts`, `server.ts`, `rerun.ts`) and the
  `/stremio-trakt-migration` skill are marked **deprecated** (not deleted) until the KevBox path is proven.

## 15. Testing

- **Unit (converter, no network — Cinemeta `episodeList` injected):** fixture Stremio library covering —
  watched movie; series with partial watched bitfield; continue-watching mid-episode; `temp` vs saved; **rev 3
  additions:** (a) series resume row with **missing season/episode** → assert it does NOT collapse to the bare
  `_id` key (skipped or S/E-derived); (b) **non-IMDB id namespace** (`kitsu:…`) watched series → assert the
  whole show is NOT dropped (episodeList iteration, not `split(':')[1]`); (c) item with **empty/absent
  `state.lastWatched` and missing `_ctime`** → assert no `NaN` reaches any payload (drop for watch_progress,
  fallback for watched_items/library); (d) finished title (`timeOffset` cleared) → assert it yields a
  watched_items row but no watch_progress row. Assert exact `progress_key` strings, ms units, "season/episode
  resolves to NULL" (not a specific encoding).
- **Integration (dry-run):** `--dry-run` against a real member's library; verify counts/shapes + source-vs-dest.
- **End-to-end:** allowlist + `--commit` + on-device restore against a real prod **merge-sensitive** member
  (not Trakt-connected). The branch DB remains available for converter integration tests that don't need a device.
- **Merge regression (rev 3 — all three subsystems):** for a MERGE-SENSITIVE member, assert (server-side + on
  device) that pre-existing KevBox **watch_progress, watched_items, AND library** survive and conflicts resolve
  to the newer timestamp — this is the §9.2/§9.3 blocker validation.

## 16. Deliverables

- `src/utils/convert-kevbox.ts` (pure, episodeList injected), `src/utils/kevbox.ts` (gate-aware, Cinemeta
  fetch, base-table verify), `src/kevbox-import.ts` (CLI), `src/kevbox-poller.ts` + `scripts/kevbox_poller.sh`.
- `member_stremio_creds` vault table (RLS, no app grants, **encrypted email+password**) + a loader to populate creds.
- **Poller ops (rev 3):** journal with `attempts` + dead-letter; **monitoring/alert on `status='failed'`** and on
  members with a `member_device` row but no `done` import; a **re-snapshot sweep at poller launch** (cohort drift).
- `scripts/kevbox_import.sh` wrapper + `~/.config/stremio-kevbox-migration/app.env` convention.
- `/stremio-kevbox-migration` skill (SKILL.md + scripts + references), replacing the Trakt skill; deprecation note.
- Converter unit tests + fixtures (incl. the §15 rev-3 cases).
- **(Pending §9.4 decision)** the scoped client merge-safety patch (option B) if chosen.
- Pointer note in the `trakt-stremio-import` repo linking to this spec; the §10 backfill-cohort operational file.

## 17. Build & rollout order

0. **Preflight (§7.3): prove `_for` EXECUTE on the poller connection — hard go/no-go.**
1. **Import core + CLI** (`convert-kevbox.ts` pure + injected episodeList, `kevbox.ts`, `kevbox-import.ts`) +
   converter unit tests (§15).
2. **Resolve §9.4** (merge-safety fix A or B) — required before any merge-sensitive commit.
3. **Canary:** allowlist + `--commit` ONE merge-sensitive, non-Trakt member → on-device verify all three
   subsystems survive (§10 step 3).
4. **Backfill the other 15** active members (manual CLI; Trakt-disconnect first for `karimassad`).
5. **Creds vault** (encrypted email+pass) + loader; pre-load for the ~320 not-yet-active members.
6. **C1 poller** on persovps (journal + alerts + re-snapshot sweep) → enable for the first-login tail; adopt
   §9.4 option B before fleet-wide if pre-existing local history is common.
7. **Deprecate** the Trakt skill once the KevBox path is proven on the fleet.

## 18. Locked decisions

- **Write path = admin `sync_push_*_for(owner, …)`** over the prod DB connection (§7.3) — only Stremio creds
  stored, no KevBox passwords; server enforces all R1–R10; zero new sync surface. **Gated on the §7.3 EXECUTE
  preflight passing.**
- Scope: watch_progress + watched_items + saved library (library toggleable, default on).
- Packaging: **replacement** — new KevBox path + new skill; Trakt deprecated.
- `--dry-run` is the **default**; `--commit` performs the push.
- `profile_id = 1` fleet-wide; no hardcoded secrets.
- **Gate stays during rollout** — enable members by allowlisting (allowlist + import), not by ungating. The
  allowlist is the permanent kill-switch.
- Trigger = `claim_device` (first-login) for the C1 auto path (has-ever-claimed; capped-out edge handled per §7.1).
- **Sequencing: manually backfill the 17 FIRST (canary on a merge-sensitive, non-Trakt member, §10), THEN
  enable C1 for the ~320 tail.**
- **Vault stores encrypted email+password** (not authKey-only) — enables unattended re-login (rev 3).
- **Merge-safety (rev 3):** `watch_progress` restore is non-destructive; **`watched_items` + `library` are
  REPLACE-not-union** and MUST be made safe (§9.4 option A or B) before backfilling any member with local
  history. The canary on-device check validates all three subsystems.

## 19. Open decisions for planning

- **§9.4 merge fix:** option (A) operational sequencing (no client change) vs (B) scoped client union patch.
  Recommend A for canary, B before fleet-wide if pre-existing local history is common.
- **Vault encryption mechanics** — column-level pgcrypto vs app-side encrypt with `KEVBOX_ENC_KEY` before insert.
  (The store-email+pass-vs-authKey question is now DECIDED: store encrypted email+pass, §18.)
- **Poller cadence + first-login detection** — delta-since-last-tick vs "all pending with a row"; confirm the
  few-minutes lag is acceptable (KevBox re-pull absorbs it). Ensure the re-snapshot sweep covers the build window.
- ~~watched_items/library merge paths — confirm union semantics~~ **RESOLVED (rev 3):** they are NOT a union —
  they REPLACE; see §9.2/§9.3. The open part is only *which* fix (above).

---

## Appendix A — Gap-analysis ledger (rev 3)

26-agent verification of every load-bearing rev-2 claim against the SQL/Kotlin/TS, each finding adversarially
re-checked. Severities are post-adversarial.

**CRITICAL (silent data loss)**
- **A1 — watched_items REPLACE-not-union** (§9.2). `WatchedItemsSyncService.kt:244-249` → `WatchedItemsPreferences.kt:222-261` (preserve gated behind `lastSuccessfulPushMs>0` at `:242`, replace `:255`, dead union `:170`). Folded into §9.2/§9.4/§10/§15/§18.

**HIGH**
- **A2 — library REPLACE-not-union** (§9.3). `StartupSyncService.kt:390-392` → `LibraryPreferences.kt:109-124`. Recoverable (cloud upsert keeps union, no delete). Folded into §6.3/§9.3/§9.4.
- **A3 — watched-bitfield "reuse" is network-coupled + Trakt-shaped** — §4 purity contradiction. `convert.ts:116-137` is async/`getCinemetaMeta`; pairs live at `:184-194`. Folded into §4/§6.2.
- **A4 — `_for` EXECUTE unprovable from repo** — sole write path. `*_setup.sql` revokes only, no grant/owner. Folded into §7.3 (preflight)/§12/§17 step 0/§18.
- **A5 — vault-only authKey → no unattended re-login** (`stremio.ts:35-61,83-89`); `result=[]` is a "successful" empty → marked done. Folded into §7.1/§12/§18.

**MEDIUM**
- **A6 — series progress_key collision** when season/episode missing (bare `_id` key). `watch_progress_setup.sql:19`. Folded into §6.1/§15.
- **A7 — `parseInt(state.watched.split(':')[1])` NaN for non-IMDB namespaces** → whole show dropped. `convert.ts:161-167`. Folded into §6.2/§15.
- **A8 — `Date.parse('')`→NaN, legacy guard not ported** (`convert.ts:19-26`); `last_watched bigint NOT NULL`. Folded into §5/§6.1/§6.3/§15.
- **A9 — series `video_id` no fallback** (movies have one). Folded into §6.1.
- **A10 — `timeOffset>0` drops finished titles** from continue-watching. Folded into §6.1/§13.
- **A11 — `karimassad`/`alecco` Trakt-connected in canary cohort** → misleading canary. Folded into §10/§14.
- **A12 — no failed-import monitoring; cohort drift** during build window. Folded into §7.1/§16.
- **A13 — cohort counts + "proven on TV" unverifiable** (on-device E2E pending). Folded into header/§10/§13.

**LOW / informational (resolved or ruled-out)**
- **A14 — season/episode omit-vs-null is cosmetic** (`nullif(...,'')::int` collapses both). Softened in §5.
- **A15 — §12 gate-closed detection must use `sync_canary_members` check**, not push no-op (`_for` is gate-independent). Folded into §3/§12.
- **A16 — non-tt ids** = degraded tile, not loss; log the ids. Folded into §6.
- **A17 (ruled out) — on-device prune** is a no-op (`WatchProgressPreferences.kt:636-638`), so large imports restore intact — no truncation risk.
- **A18 (false positive, killed) — "claim_device never fires for capped members"**: a slot-consumed member already HAS a row from their genuine first login. Residual (poller-delta / cohort drift) folded into §7.1.

**CONFIRMED solid (no change needed):** public + `_for` RPC arg names/order (`p_profile_id` second); all payload keys read by the bodies; `progress_key` `_sNeM` matches the client; ms/epoch-ms units; `content_type` strings; library column defaults; `get_sync_owner` closed-by-default + `sync_canary_members` location; every wrapper owner-gates push/pull; `_for` gate-independent; client gating + Trakt-disconnect flip; cohort SQL columns (`watch_seconds`); exactly seven `sync_push_*_for`.
