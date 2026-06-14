# Stremio → KevBox Direct Import (Trakt-free) — Design Spec

**Date:** 2026-06-14 (**rev 2** — same day; revised after the cloud-restore **canary gate** shipped and was proven on prod)
**Status:** Design approved → ready for implementation **planning**
**Depends on:** [`2026-06-14-cloud-restore-design.md`](./2026-06-14-cloud-restore-design.md) — **now DEPLOYED to live prod** (`scmqdptagksltnwiveyh`) with the canary allowlist gate, and proven end-to-end on the operator's TV. Its §4/§5 RPC contract, §8 gating, and the new `get_sync_owner` **canary gate** are all load-bearing here.
**Code lives in:** `~/projects/trakt-stremio-import` (a patched fork of `aliyss/trakt-stremio-import`).

> **rev 2 — what changed and why.** rev 1 assumed an operator-run CLI at member *onboarding*, with an **ungated** `get_sync_owner()` and "no per-member flag." Three things changed since:
> 1. **The canary gate shipped.** `get_sync_owner()` is now CLOSED BY DEFAULT — it returns a member's owner id only if they are in `public.sync_canary_members`, else `NULL`. The importer writes through the same `sync_push_*` RPCs, so **an import silently no-ops for any non-allowlisted member.** The gate is now the per-member rollout control rev 1 said didn't exist.
> 2. **Migration is gradual (~10 months), and existing members have no future onboarding hook.** The ~330 members are already provisioned; their real switch point is **first KevBox login**, not onboarding. So the trigger is first-login, not onboarding.
> 3. **The merge against existing local history was traced in the client** (`WatchProgressPreferences.mergeRemoteEntries`) — it is non-destructive and recency-based; the gate's side effect (`lastSuccessfulPushMs = 0` for never-synced members) fully protects local history. This makes already-active members safe to backfill through the same path.
>
> rev 2 keeps the verified RPC contract (§5) and field mapping (§6) from rev 1 unchanged, and rewrites the **rollout/trigger/gating/merge/cohort** around the gate.

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

**Non-goals:** the server schema (delivered + deployed by cloud-restore); any Android client changes
(none); collections/profiles/addons/plugins (no Stremio source); multi-profile (`profile_id = 1`).

## 2. Background — why this works without Trakt

The Trakt detour existed only because KevBox had no server-side restore schema; the device could get
cross-device history *only* via Trakt scrobbling (forward-only, never backfills). Cloud-restore now
deploys the `sync_*` RPCs, so the device restores watch progress / watched items / library straight from
KevBox Supabase. The cloud-restore **§8 client gating** makes a non-Trakt member exactly the case where
the Supabase path is live:

| Subsystem | Runs for a non-Trakt member? |
|---|---|
| watch_progress, watched_items | **Yes** (`shouldUseSupabaseWatchProgressSync()` true unless Trakt is the progress source) |
| library | **Yes** (skipped only when `librarySourceMode == TRAKT`) |

So injecting Stremio history into a member's Supabase rows is enough for it to flow down on next login.
No client edits, no Trakt account.

## 3. The canary gate interaction (load-bearing, new in rev 2)

`get_sync_owner()` resolves the owner for **every** sync RPC via `nullif(get_sync_owner(),'')::uuid`. It
now returns the caller's uid **only if** they are in `public.sync_canary_members`, else `NULL`.
Consequences for the importer:

- **Writes require allowlisting.** A member JWT calling `sync_push_*` while not allowlisted resolves to a
  `NULL` owner → R4 no-op → **zero rows written, no error.** The verify step (`sync_pull_*`) is *also*
  owner-gated, so it reads back 0 and looks "successfully empty." **The importer must be gate-aware:**
  before pushing, confirm the member resolves a non-null owner (or the runner allowlists them first), and
  treat "owner is NULL" as a hard precondition failure — not a successful empty import.
- **Restore ALWAYS requires allowlisting** (regardless of the write path). Even after a successful import,
  the member's device only pulls the seeded data once they are allowlisted. So **enabling a member =
  allowlist + import**. (If the write uses §7.3 option (b)'s admin `_for` path, the *write* itself is
  gate-independent — but restore still needs the member allowlisted, so the allowlist step remains.)
- **The gate IS the rollout control.** Enabling members one at a time (allowlist + import) confines blast
  radius per member. The allowlist remains the permanent kill-switch (`truncate public.sync_canary_members`
  disables all sync instantly; delete one row to disable one member). We do **not** ungate to `select
  auth.uid()::text` during the 10-month rollout — we accumulate allowlisted members instead.

## 4. Architecture & components

Built as new entrypoints in the existing fork, reusing its proven Stremio-reading half. Trakt code stays
in place but becomes legacy (§13).

| Component | File | Responsibility | New? |
|---|---|---|---|
| Stremio reader | `src/utils/stremio.ts` | login (email+pass → authKey), `getLibrary`, `getCinemetaMeta` | reuse |
| Converter | `src/utils/convert-kevbox.ts` | Stremio library → `{watchProgress[], watchedItems[], library[]}`, incl. watched-bitfield decode. **Pure, network-free, unit-testable.** | **new** |
| KevBox client | `src/utils/kevbox.ts` | member auth + batched `sync_push_*` + verify via `sync_pull_*`; gate-aware owner precondition check | **new** |
| CLI entrypoint | `src/kevbox-import.ts` | orchestrate fetch → convert → auth → (dry-run \| commit) → verify. Used for the **manual backfill** of the 17 and for ad-hoc re-runs. | **new** |
| Auto runner (C1) | `src/kevbox-poller.ts` (+ `scripts/kevbox_poller.sh`) | the C1 poller: scan for newly-logged-in members with stored creds + `pending` status → enable + import + verify + mark `done`. Thin wrapper around the same converter + client core. | **new** |

The converter is the only correctness-bearing pure unit; `kevbox.ts` owns all KevBox I/O; the CLI and the
poller are two thin orchestrators over the same core. No new npm deps (`axios`, `stremio-watched-bitfield`
already present).

## 5. Verified client contract (unchanged from rev 1)

All param names/order, payload keys, and return shapes were verified against the live client call sites.
PostgREST binds by argument **name** — deploy these exact names.

**Push RPCs (the importer calls these):**

| RPC | Args | Payload item shape |
|---|---|---|
| `sync_push_watch_progress` | `p_entries jsonb`, `p_profile_id int` | `{content_id, content_type, video_id, season?, episode?, position, duration, last_watched, progress_key}` — `season`/`episode` **omitted when null** (movies) |
| `sync_push_watched_items` | `p_items jsonb`, `p_profile_id int` | `{content_id, content_type, title, season, episode, watched_at}` — `season`/`episode` **explicit JSON null** for movies (R6) |
| `sync_push_library` | `p_items jsonb`, `p_profile_id int` | `{content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating?, genres[], addon_base_url, added_at}` |

**Pull RPCs (verification only):** `sync_pull_watch_progress(p_profile_id, p_since_last_watched DEFAULT null, p_limit DEFAULT null)`, `sync_pull_watched_items(p_profile_id, p_page, p_page_size)`, `sync_pull_library(p_profile_id, p_limit, p_offset)`.

**Verified invariants:**
- `progress_key` = `content_id` for movies; `` `${content_id}_s${season}e${episode}` `` for episodes (`_sNeM`, not colon-delimited).
- `position`/`duration` are **milliseconds**; Stremio's `state.timeOffset`/`state.duration` are also ms — no conversion.
- `content_type` is `"movie"`/`"series"` — identical to Stremio's `type`.
- `last_watched`/`watched_at` are **epoch-ms `int8`**; Stremio's `state.lastWatched` is ISO → `Date.parse(...)`.
- `profile_id` = **1** fleet-wide.

## 6. Field mapping (unchanged from rev 1)

Source: `StremioLibraryObject`/`StremioLibraryObjectState`. Iterate the library, skip `removed`.

**Content-id namespace:** usually IMDB ids (`tt…`). Non-standard ids (`kitsu:…`, addon-specific) pass
through as-is (may not resolve on-device); the converter logs a count of non-`tt`/`tmdb:`/`trakt:` ids
rather than dropping them.

### 6.1 watch_progress — one entry per title
Emit when `state.timeOffset > 0 && state.duration > 0` and not the junk case (`position ≤ 1 && duration ≤ 1`):
- common: `content_id=_id`, `content_type=type`, `position=state.timeOffset`, `duration=state.duration`, `last_watched=Date.parse(state.lastWatched)`
- movie: `video_id = state.video_id || _id`, `progress_key = _id`, season/episode omitted
- series: `video_id = state.video_id`, `season/episode = state.season/episode`, `progress_key = ${_id}_s${season}e${episode}` (only when both present)

### 6.2 watched_items — every watched movie + episode
- movie: emit iff `state.flaggedWatched === 1` → `{content_id:_id, content_type:"movie", title:name, season:null, episode:null, watched_at}`
- series: decode `state.watched` via `stremio-watched-bitfield` against the Cinemeta episode list (reuse `convert.ts:116-137`); for each watched ep → `{content_id:_id, content_type:"series", title:name, season, episode, watched_at}`
- `watched_at` = parsed `state.lastWatched`, fallback `Date.now()`. **rev 2 caveat:** for watch_progress (resume positions) we use the real Stremio timestamp only (no `Date.now()` fallback) so a stale Stremio position can never out-rank a fresher KevBox play under the merge (§9). The `Date.now()` fallback stays only for watched_items, where the merge is a union and the timestamp is benign metadata.

### 6.3 library — saved titles *(toggleable, default on)*
Filter `!removed && !temp`: `{content_id:_id, content_type:type, name, poster, background, release_info:year, added_at:Date.parse(_ctime), poster_shape:"POSTER", genres:[], addon_base_url:"", description:"", imdb_rating:null}`. genres/rating/addon_base_url have no Stremio source (nullable/defaulted per cloud-restore §5.3). Behind `--no-library`.

## 7. Rollout model (rev 2) — C1 auto-on-login + manual backfill of the 17

### 7.1 Automatic path (C1) — the ~320 future switchers
**Trigger = first `claim_device`.** When a member first logs into KevBox, the one-device access control
claims their device (a `member_device` row appears). `claim_device`/access resolve `auth.uid()` directly
and do **not** consult the sync gate (cloud-restore §3), so a member can log in + claim a device while
still gated-off for sync. This is a reliable server-side first-use signal that needs **no client change
and no touching the fragile login Edge Function.**

**Runner = persovps poller** (`kevbox-poller.ts`, cron). Each tick:
1. Find members with a stored Stremio authKey + import `status = pending` + a `member_device` claim.
2. For each: **allowlist** them (`insert into sync_canary_members`) → run the import core (Stremio fetch →
   convert → write → verify) → mark `status = done` (or `failed` with the error for retry).

**Why a poller, not a login webhook (C2) or in-Deno (C3):** C1 reuses the existing TS importer verbatim,
never touches the login path or client, and is trivially retryable. The few-minutes lag is absorbed by
KevBox re-pulling on every app start. C2 (login webhook → runner) is a later upgrade if instant capture
matters; C3 (port to a Deno Edge Function) only if the VPS dependency must go.

**Credential vault.** A secured table (e.g. `member_stremio_creds(user_id uuid pk, stremio_authkey text,
status text default 'pending', last_run timestamptz)`), RLS on, **no grants to anon/authenticated**
(read only by the admin poller / a SECURITY DEFINER context), authkey **encrypted at rest** with the
existing `KEVBOX_ENC_KEY`. The operator pre-loads each member's authKey (operator holds all members'
Stremio creds). Mirrors the `sync_canary_members` ACL posture.

### 7.2 Manual backfill — the 17 already-active members
Their first login is in the past, so C1's trigger never fires for them. Each gets a one-time
**allowlist + `kevbox-import.ts --commit`** run. This is also the **canary cohort** for the whole pipeline
(§10). The CLI and the poller share the same import core, so the manual runs validate the exact code C1
will later automate. See §10 for the cohort and the canary-first sequencing.

### 7.3 Writes-as-member: DECIDED — admin `_for` (option b), verified
The runner writes owner-scoped rows by calling the inner `sync_push_*_for(p_owner, …)` functions directly
over the **prod admin DB connection** the poller already holds (for allowlist + vault). **Verified
2026-06-14:** the connection role is `postgres` and **can EXECUTE all seven `sync_push_*_for` functions**
(they are EXECUTE-revoked from app roles, but the role owns them). The inner functions take an explicit
owner and enforce all R2/R3 correctness — the importer shapes payloads only.

There are two distinct credentials: **#1 Stremio** email+pass → authKey (READ the source; needed in every
option; the operator has all of them; obtained via `POST api.strem.io/api/login`, cf. `stremio.ts:17` /
`stremio_login.sh`), and **#2 KevBox** member password → member JWT (WRITE as the member). **Option (b)
needs only #1** — it writes via the admin connection with an explicit owner uid, so **no KevBox member
passwords (#2) are ever stored or used.** Rejected: (a) member-JWT-via-password-grant (would store ~330
KevBox passwords) and (c) minted-JWT (would hold the project JWT secret, which can impersonate anyone).
Both the manual CLI and the C1 poller use (b) — a single write path.

**⚠️ Arg order differs from the public wrappers.** The `_for` signatures are `(p_owner, p_profile_id,
payload)` — `sync_push_watch_progress_for(owner, 1, entries)`, `sync_push_watched_items_for(owner, 1,
items)`, `sync_push_library_for(owner, 1, items)` — note `p_profile_id` is **second**, unlike the public
`sync_push_watch_progress(p_entries, p_profile_id)`. The plan must use the `_for` order.

The `_for` write is **gate-independent** (explicit owner), so import can run before allowlisting; but
**restore still requires the member allowlisted**, so enabling a member is still allowlist + import.

## 8. Auth & write path

- **Stremio:** authKey from the vault (or email+pass at CLI time) → `api.strem.io`.
- **KevBox writes:** per §7.3. The RPCs/`_for` functions enforce all R1–R10 correctness server-side — the
  importer carries **zero** correctness logic, it only shapes payloads.
- `SUPABASE_URL`/anon key from `~/.config/stremio-kevbox-migration/app.env` (copied from NuvioTV
  `local.properties`). The poller additionally holds the prod admin DB connection (for vault + allowlist
  + option (b) writes). **No secret hardcoded;** creds touch only `api.strem.io` and KevBox endpoints.

## 9. Merge semantics — verified non-destructive (new in rev 2)

The import only **seeds the cloud**; the device does pull → merge-into-local → push on its next start. The
import never runs on the device. Conflict resolution was traced in the client:

**`WatchProgressPreferences.mergeRemoteEntries`** (the restore-pull merge, `WatchProgressPreferences.kt`):
- **No local loss for never-synced members.** The "remove local entries missing from remote" step
  (`:350-365`) protects any local entry with `lastWatched > lastSuccessfulPushMs`. A gated-off member has
  **never successfully pushed → `lastSuccessfulPushMs = 0`**, so *every* real local entry is protected.
  Importing Stremio data cannot delete their existing KevBox history.
- **Conflicts resolve by recency** (`:367-378`): remote (Stremio) overwrites local only if
  `remote.lastWatched > local.lastWatched`; otherwise local is kept. Most-recent play wins — correct.
- **Server re-applies the same rule.** On the subsequent push, R2's `ON CONFLICT … WHERE EXCLUDED.last_watched
  > existing.last_watched` re-guards, so it is order-independent (client and server agree).

**watched_items** merge is a **union** (keyed `content_id, season, episode`): watched on either platform →
watched; the `watched_at` timestamp is benign metadata, never un-watches. **library** is likewise additive
(union of saved titles). *(These two are additive by nature; their exact merge paths will be confirmed in
planning the same way, but the loss risk is structurally low.)*

**Net:** no KevBox history lost, no stale Stremio position overwriting a fresher KevBox one. The already-
active 17 flow through the **same** path as fresh members — the gate's `lastSuccessfulPushMs = 0` side
effect is what makes them safe, so no separate backfill merge logic is needed.

## 10. Already-active backfill cohort (the 17)

**Definition:** members with a `member_device` claim = have logged into KevBox at least once = the cohort
C1's first-login trigger will miss. **Regenerate the snapshot any time** with:

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

**Snapshot (2026-06-14): 17 members.** The concrete list (emails + auth UIDs) is kept **out of this
committed spec to avoid PII in git** — it lives in the gitignored operational file
`~/.config/stremio-kevbox-migration/backfill-cohort-2026-06-14.md`.

**Merge-risk classifier = `watch_seconds > 0`:**
- **MERGE-SENSITIVE (6 of 17)** — have real local KevBox history → the merge path that must hold (§9). These
  are the ideal canary targets (real history to protect).
- **clean (10 of 17)** — logged in, never watched → no local history → import is a plain restore, no conflict.
- (Row 1 is the operator — already the cloud-restore canary, already allowlisted.) Backfill cohort = the
  other 16.

**Canary-first sequencing:**
1. Build the import core + CLI (§4).
2. **Allowlist + `--dry-run` then `--commit`** ONE merge-sensitive member (recommend the most-watched) →
   verify on the real TV: continue-watching/history/library restored, **no lost local history, no regressed
   positions.** This proves the hardest path (existing local history) before any automation.
3. Backfill the remaining 15 (the other 5 merge-sensitive, then the 10 clean).
4. **Only then** build/enable the C1 poller for the ~320 future switchers.

## 11. Operator workflow, dry-run & idempotency

- **Manual (CLI):** `kevbox_import.sh <stremio_authkey|email pass> <member_uid|kevbox_email>` — reads
  `SUPABASE_URL`/anon from `~/.config/stremio-kevbox-migration/app.env`. **Defaults to `--dry-run`**
  (prints the three payload arrays + counts, pushes nothing). Eyeball counts → re-run `--commit`. Then
  report verify counts (server pull = ground truth).
- **Auto (poller):** no per-member operator action; it dry-runs internally only as a shape check, then
  commits, then verifies, then marks `done`.
- **Idempotent & safe to re-run:** server R2 `last_watched`/`watched_at` guards + R3 unique keys mean
  re-imports never duplicate or regress. Top-ups (member watched more in Stremio later) = run again.
- Archive each member's run to `accounts/<member>.json` under the tool dir (mirrors the Trakt skill).

## 12. Error handling

- **RPC not deployed:** `404`/`PGRST202` → fail-fast "cloud-restore schema not deployed" → exit non-zero.
- **Gate closed for this member (new):** owner resolves `NULL` (push no-ops, pull empty) → fail-fast
  "member not allowlisted / not enabled for sync" → exit non-zero. **Never report a gated-off no-op as a
  successful empty import.** The runner allowlists before importing, so this should only fire on a bug.
- **Auth failure:** distinguish Stremio login vs KevBox auth failure.
- **Cinemeta lookup blip:** failed episode-list fetch logs a warning, skips that show's *episodes*
  (continue-watching + movie rows still go); re-run later to fill.
- **Batch failure:** abort with batch offset/size + PostgREST body; already-pushed batches are safe.
- No foreground `sleep` (sandbox-blocked); in-process pauses only.

## 13. Verification

After `--commit`, read back via `sync_pull_watch_progress`, `sync_pull_watched_items` (page 1),
`sync_pull_library`; report counts. **Server pull = ground truth — never claim success from the push log.**
A `--verify-only` mode runs just this. For MERGE-SENSITIVE members, verification is **on-device** (the TV)
not just row counts — confirm existing KevBox continue-watching survived and nothing regressed (§10 step 2).

## 14. Trakt deprecation & the one existing Trakt user

- **New members:** pure Stremio → KevBox; Trakt never touched.
- **Existing Trakt user(s):** run the importer (Stremio → Supabase), then **disconnect Trakt on their
  device** — while Trakt is the progress source, the Supabase watch/library sync is client-gated *off*
  (§2); disconnecting flips `shouldUseSupabaseWatchProgressSync()` true so the imported data restores.
  Documented as a one-time manual step in the skill. (`accounts/` shows `alecco`, `karimassad` migrated via
  Trakt; `karimassad` is also in the active-17, so they're an ideal A/B check.)
- The Trakt tool (`convert.ts`, `trakt.ts`, `sync.ts`, `server.ts`, `rerun.ts`) and the
  `/stremio-trakt-migration` skill are marked **deprecated** (not deleted) until the KevBox path is proven.

## 15. Testing

- **Unit (converter, no network):** fixture Stremio library — watched movie, series with partial watched
  bitfield, continue-watching mid-episode, `temp` vs saved → assert exact payload shapes, `progress_key`
  format, ms units, movie season/episode null handling (omitted for watch_progress, explicit null for
  watched_items).
- **Integration (dry-run):** `--dry-run` against a real member's library; verify counts/shapes.
- **End-to-end:** now that cloud-restore is **on prod with the gate**, the canary is a real prod member
  (the §10 merge-sensitive canary) — allowlist + `--commit` + on-device restore — *not* the branch DB
  (the branch lacks the prod login path anyway). The Supabase branch DB remains available for converter
  integration tests that don't need a real device login.
- **Merge regression:** for a MERGE-SENSITIVE member, assert (server-side pulls + on-device) that
  pre-existing KevBox entries survive and conflicts resolve to the newer `last_watched`.

## 16. Deliverables

- `src/utils/convert-kevbox.ts`, `src/utils/kevbox.ts` (gate-aware), `src/kevbox-import.ts` (CLI), and
  `src/kevbox-poller.ts` + `scripts/kevbox_poller.sh` (C1) in the fork.
- `member_stremio_creds` vault table setup (RLS, no app grants, encrypted authkey) + a loader for the
  operator to populate authKeys.
- `scripts/kevbox_import.sh` wrapper + `~/.config/stremio-kevbox-migration/app.env` convention.
- `/stremio-kevbox-migration` skill (SKILL.md + scripts + references), replacing the Trakt skill;
  deprecation note on the old skill.
- Converter unit tests + fixtures.
- Pointer note in the `trakt-stremio-import` repo linking to this spec.
- The §10 backfill-cohort operational file (gitignored, already created).

## 17. Build & rollout order

1. **Import core + CLI** (`convert-kevbox.ts`, `kevbox.ts`, `kevbox-import.ts`) + converter unit tests.
2. **Resolve §7.3** (writes-as-member) during planning.
3. **Canary:** allowlist + `--commit` ONE merge-sensitive active member → on-device verify (§10 step 2).
4. **Backfill the other 15** active members (manual CLI).
5. **Creds vault** + loader; pre-load Stremio authKeys for the ~320 not-yet-active members.
6. **C1 poller** (`kevbox-poller.ts`) on persovps → enable for the future first-login tail.
7. **Deprecate** the Trakt skill once the KevBox path is proven on the fleet.

## 18. Locked decisions

- **Write path = admin `sync_push_*_for(owner, …)`** over the prod DB connection (§7.3, verified) — only
  Stremio creds stored, no KevBox passwords; server enforces all R1–R10 correctness; zero new sync surface.
- Scope: watch_progress + watched_items + saved library (library toggleable, default on).
- Packaging: **replacement** — new KevBox path + new skill; Trakt deprecated.
- `--dry-run` is the **default**; `--commit` performs the push.
- `profile_id = 1` fleet-wide; no hardcoded secrets.
- **Gate stays during rollout** — enable members by allowlisting (allowlist + import), not by ungating.
  The allowlist is the permanent kill-switch.
- Trigger = `claim_device` (first-login) for the C1 auto path.
- **Sequencing: manually backfill the 17 already-logged-in members FIRST (canary on a merge-sensitive
  member, §10), THEN enable C1 for the ~320-member tail.**
- Merge is non-destructive and recency-based; already-active members use the same path (§9).
- **Hard precondition:** cloud-restore deployed (DONE — on prod with the gate).

## 19. Open decisions for planning

- **Vault encryption mechanics** — column-level pgcrypto vs app-side encrypt with `KEVBOX_ENC_KEY` before
  insert; and whether to store the Stremio **email+password** (lets the poller re-login for a fresh authKey
  unattended — authKeys can go stale, cf. `loginWithToken`) or just the authKey.
- **Poller cadence + first-login detection** — poll `member_device` for new claims since the last tick
  (cron interval); confirm the few-minutes lag is acceptable (KevBox re-pull absorbs it).
- **watched_items/library merge paths** — confirm union semantics in the client the same way §9 did for watch_progress.
