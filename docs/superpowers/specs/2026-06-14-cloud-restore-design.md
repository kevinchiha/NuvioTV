# Cloud Restore for KevBox TV — Design Spec

**Date:** 2026-06-14 (revised same day after gap-analysis audit)
**Branch:** `feat/watch-progress-sync`
**Status:** Design revised against the live client contract → ready for implementation **planning** (Superpowers will turn this into plans before any SQL is written)
**Backend:** KevBox Supabase project `scmqdptagksltnwiveyh`

> **Revision note.** The first draft of this spec described the server RPCs from memory.
> A code-grounded audit (24-agent workflow + manual verification, 2026-06-14) found
> that several §4 signatures were paraphrased inaccurately and that several
> load-bearing correctness rules were unstated. Because PostgREST resolves a function
> by the **exact set of argument names** and kotlinx deserialization throws on any
> missing non-null field, "approximately right" signatures fail hard. This revision
> replaces §4 with the **verified** contract (every entry cross-checked against the
> `core/sync/*SyncService.kt` call site and the `Supabase*` model) and adds the
> cross-cutting server-correctness requirements (§4, §10, §11) the implementation
> plan must honour.

## 1. Goal

A member uninstalls/reinstalls KevBox TV, or signs in on a fresh TV. After login,
their **watch progress, watch history, library, collections, and settings are
restored** from the cloud.

This is achieved with **pure `auth.uid()`-scoped storage** on the existing KevBox
Supabase project. The Android client already ships the entire sync implementation
(inherited from upstream NuvioTV); only the server-side schema is missing.

**Non-goal:** multi-device *linking* (sharing one library across several TVs via a
"sync owner"), member-controlled addon/plugin sync, multi-profile management, and
PIN/avatar profile features. These are excluded by design (see §7).

## 2. Background / current state

- The Android client contains a complete `core/sync/` package
  (`WatchProgressSyncService`, `WatchedItemsSyncService`, `LibrarySyncService`,
  `CollectionSyncService`, `HomeCatalogSettingsSyncService`,
  `ProfileSettingsSyncService`, `ProfileSyncService`, plus addon/plugin services),
  wired into `WatchProgressRepositoryImpl`, `StartupSyncService`, and
  `AccountViewModel`. It talks to Supabase via PostgREST RPC.
- The client's Supabase project (`BuildConfig.SUPABASE_URL`) is **already the KevBox
  project** — the same one holding `member_addon`, `member_access`, and telemetry.
  No re-pointing is needed.
- The ~330 members are **real Supabase Auth users**; `member_addon` RLS
  (`auth.uid() = user_id`) works in production, proving members hold live JWT
  sessions. The Supabase client installs `Auth` with persisted, auto-refreshing
  sessions (`SupabaseModule.kt`).
- **Live introspection (2026-06-14):** zero `watch_progress`/`sync_*` tables or
  functions exist. The backend is entirely absent; the client is complete. Custom
  functions present are KevBox's own (`claim_device`, `record_heartbeat`,
  `get_access_verdict`, …) and do **not** collide with the upstream names.

## 3. Core architecture decision — the `get_sync_owner()` collapse

Upstream routes **every** data RPC through a "sync owner" indirection
(`get_sync_owner()`), the linchpin of its multi-device linking feature. The client
calls `get_sync_owner` (no args; `AuthManager.kt:132`, decoded as a **scalar
`text`** via `decodeAs<String>()`) and the server RPCs use it internally to decide
whose rows to read/write (`WatchProgressSyncService.kt:248-249`: *"Uses
get_sync_owner() server-side to fetch the correct user's data, bypassing RLS"*). The
client passes **no** user/owner id for the data RPCs.

KevBox is **one-device-per-member**, so sync-owner ≡ `auth.uid()`. We define a single
resolver returning a scalar text value:

```sql
create or replace function public.get_sync_owner()
  returns text
  language sql
  security definer
  set search_path = ''
as $$ select auth.uid()::text $$;
```

Consequences:
- Every data RPC resolves the owner to `auth.uid()` — same-login = same-data.
- The **entire device-linking subsystem can be omitted** while the client still
  behaves correctly (no client edits).

**Required properties (verified against callers):**
- **Return type is scalar `text`, never a table.** `getEffectiveUserId` does
  `decodeAs<String>()`; a `RETURNS TABLE`/`SETOF` would break the decode.
- **NULL-owner is unreachable on the happy path but must fail closed.**
  `getEffectiveUserId` early-returns when `currentUserId == null`
  (`AuthManager.kt:124`), so the RPC is not called without a JWT. If it ever is,
  `auth.uid()` is NULL → `select null::text` → `decodeAs<String>()` throws → caller
  falls back to its own id or null (fail-closed). The **data** RPCs must
  independently reject a NULL owner (see §4, R4).

**Behavioural-neutrality note (corrects the earlier "bonus" framing).** Today
`get_sync_owner` 404s on the KevBox backend, so `getEffectiveUserId` already returns
the member's own `auth.uid()` via its `fallbackToOwnIdOnFailure` path. After deploy
it returns `auth.uid()` explicitly. For KevBox's one-device topology these are the
**same value**, so the deploy is behaviourally neutral — it does **not** "fix a
fail-open." The access kill-switch (`get_access_verdict`), device-claim
(`claim_device`), and telemetry resolve `auth.uid()` directly and **never** consult
`getEffectiveUserId`/`get_sync_owner` (`AccessControlService.kt:25-28` is only a
comment), so redefining it cannot regress access, device-claiming, or telemetry for
the 330 members. The `getEffectiveUserId` cache is correctly invalidated across
login/logout/unauth. **Regression test still required** (§9, T-REG) because
`CREATE OR REPLACE` would silently overwrite a pre-existing `get_sync_owner` — confirm
none exists before deploy.

## 4. Cross-cutting server-correctness requirements

These rules apply to **every** table/RPC below and are the corrections the audit
surfaced. The implementation plan and SQL must satisfy all of them; §9 lists the
tests that prove each.

- **R1 — Owner scoping inside `SECURITY DEFINER`.** The client sends **no owner id**
  and all members share `profile_id = 1`; isolation cannot come from the
  `p_profile_id` argument. Every **read** RPC is either (a) `SECURITY DEFINER` with an
  explicit `WHERE user_id = get_sync_owner()` (recommended — matches the client's
  documented "bypass RLS" expectation), or (b) `SECURITY INVOKER` relying on a
  `select`-own RLS policy. **Never** a DEFINER read without the explicit owner
  predicate — that returns every member's rows for profile 1 (cross-member leak /
  wrong-member restore). Every **write/delete** RPC scopes its mutation to
  `user_id = get_sync_owner()`.
- **R2 — Upsert conflict policy is `last_watched`/`watched_at`-guarded, never
  last-write-wins.** The per-play single push is **not** gated on the startup pull
  (`pushSingleToRemote`), so a stale local value can arrive before/after a newer
  cloud value in any order. Watch-progress upserts must
  `... ON CONFLICT (...) DO UPDATE ... WHERE EXCLUDED.last_watched > <table>.last_watched`
  (and append a delta event **only when the row actually changed**). Apply the
  analogous `watched_at` guard to watched-items. Order-of-arrival must not be able to
  regress progress.
- **R3 — Upsert targets need explicit UNIQUE keys.** The client pushes idempotently
  with no row id, expecting upsert. Mandate:
  `watch_progress UNIQUE(user_id, profile_id, progress_key)`;
  `watched_items UNIQUE(user_id, profile_id, content_id, season, episode)` (NULL-aware —
  see R6); the blob tables `UNIQUE(user_id, profile_id, platform)` for settings/home-catalog
  and `UNIQUE(user_id, profile_id)` for collections; `profiles UNIQUE(user_id, profile_index)`.
  A missing/incorrect `ON CONFLICT` target either errors every push or duplicates rows
  forever. (Mirrors `member_addon_setup.sql`.)
- **R4 — NULL-owner rejection.** `user_id` is `NOT NULL` on every table; every
  push/delete RPC begins with `if get_sync_owner() is null then return/raise` (mirrors
  `member_telemetry_setup.sql` / `member_access_setup.sql`). Prevents orphaned,
  unrestorable, collision-prone rows.
- **R5 — Delta cursors are `coalesce(max(event_id), 0)`.** Both cursor RPCs are
  decoded into a **non-nullable** `Long` (`decodeAs<Long>()`). A new member / empty
  event log makes a bare `max()` return SQL NULL → decode throws. Watch-progress would
  degrade to a snapshot; **watched-items has no such guard** (`WatchedItemsSyncService.kt:246`
  is unwrapped), so a NULL cursor breaks watched-history restore outright. Both cursor
  functions must return scalar `bigint` `= coalesce(max(event_id), 0)`.
- **R6 — NULL-aware episode matching, and the two delete shapes differ.** Movies push
  `season`/`episode` as SQL NULL; series push integers. Equality must use
  `IS NOT DISTINCT FROM` (or a normalized sentinel) for both the watched-items unique
  key and delete matching, or movie rows never match and deletes silently no-op.
  **The two delete RPCs take different `p_keys` shapes** (R: array of plain strings vs
  array of objects — see §5); they are not symmetric.
- **R7 — Pull/delta return columns are non-null and complete.** kotlinx throws if any
  non-default field is missing/NULL on decode. Every `RETURNS TABLE`/`SETOF` pull and
  delta must populate the model's required columns (see §5 per-subsystem "required
  non-null" lists), including `COALESCE(video_id, '')` and **zeroed**
  `position/duration/last_watched/watched_at` on DELETE-operation event rows.
- **R8 — Stable, deterministic ordering for paged/delta reads.** Snapshot pages
  (`sync_pull_watched_items` p_page/p_page_size, `sync_pull_library` p_limit/p_offset)
  and delta pages must use a deterministic `ORDER BY` with a unique tiebreaker (e.g.
  `watched_at, content_id, coalesce(season,-1), coalesce(episode,-1)` for watched
  items; `event_id ASC` for deltas). The client stops on a short page and advances its
  cursor by `max(event_id)`; non-deterministic order drops/duplicates rows across page
  boundaries (page size = 900 for events/watched-items, 500 for library).
- **R9 — Idempotent setup, scoped teardown.** `*_setup.sql` uses
  `create … if not exists` / `create or replace` and is safe to re-run.
  `*_teardown.sql` drops **only** the newly-created objects and never touches
  `member_*` / `kevbox_*` / telemetry (mirrors the existing `member_*` convention).
- **R10 — Append-only event logs need a retention policy.** `*_events` tables grow
  unbounded across 330 members; ship a `prune`-style function (mirroring
  `prune_telemetry`) and document its cadence.

## 5. Verified RPC & table contract

All param names/order, payload keys, and return shapes below are taken **verbatim**
from the client call sites. PostgREST binds by argument **name**, so deploy these
exact names; optional args use `DEFAULT`. All tables are `auth.uid()`-scoped, carry
`user_id uuid NOT NULL` and `profile_id int NOT NULL DEFAULT 1`, enable RLS with a
`select`-own policy, and **revoke** default anon/`authenticated` write/truncate grants
(writes happen only through the `SECURITY DEFINER` RPCs).

### 5.1 Watch progress — event-sourced, delta sync
Tables: `watch_progress` (state), `watch_progress_events` (append-only log; `event_id`
`generated always as identity` — a monotonic non-null bigint).
State model `SupabaseWatchProgress` — required non-null: `user_id, content_id,
content_type, video_id, position(int8), duration(int8), last_watched(int8 epoch-ms),
progress_key`; optional: `id, season, episode, profile_id`.
Event model `SupabaseWatchProgressEvent` — required: `event_id, operation, progress_key,
content_id, content_type, position, duration, last_watched`; `video_id` defaults `''`;
`season/episode` nullable.

| RPC | Args (exact) | Returns / decode | Notes |
|---|---|---|---|
| `sync_push_watch_progress` | `p_entries jsonb`, `p_profile_id int` | (ignored) | `p_entries` = array of `{content_id, content_type, video_id, season?, episode?, position, duration, last_watched, progress_key}` (season/episode **omitted** when null). Upsert per R2/R3; append event only on change. |
| `sync_pull_watch_progress` | `p_profile_id int`, `p_since_last_watched int8 DEFAULT null`, `p_limit int DEFAULT null` | `SETOF` → `decodeList<SupabaseWatchProgress>` | All 8 required columns non-null (R7). |
| `sync_get_watch_progress_delta_cursor` | `p_profile_id int` | scalar `bigint` → `decodeAs<Long>` | `coalesce(max(event_id),0)` (R5). Defense-in-depth here — the client wraps this call with a snapshot fallback (`WatchProgressSyncService.kt:340-345`); the §5.2 watched-items cursor is the **unwrapped, must-never-error** case. |
| `sync_pull_watch_progress_delta` | `p_profile_id int`, `p_since_event_id int8`, `p_limit int` | `SETOF` → `decodeList<SupabaseWatchProgressEvent>` | `where user_id=get_sync_owner() and event_id>p_since_event_id order by event_id asc limit p_limit` (R1/R7/R8). |
| `sync_delete_watch_progress` | `p_keys jsonb`, `p_profile_id int` | (ignored) | `p_keys` = **array of plain `progress_key` strings**. Append delete events. |

### 5.2 Watched items — event-sourced, delta sync
Tables: `watched_items` (state), `watched_items_events` (append-only).
State model `SupabaseWatchedItem` — required: `content_id, content_type, watched_at(int8)`;
`title` defaults `''`; `season/episode` nullable; `user_id/id/profile_id` optional.
Event model `SupabaseWatchedItemEvent` — required: `event_id, operation, content_id,
content_type, watched_at`; `title` defaults `''`.

| RPC | Args (exact) | Returns / decode | Notes |
|---|---|---|---|
| `sync_push_watched_items` | `p_items jsonb`, `p_profile_id int` | (ignored) | `p_items` = array of `{content_id, content_type, title, season, episode, watched_at}`; season/episode sent as **explicit JSON null** for movies (R6). |
| `sync_pull_watched_items` | `p_profile_id int`, `p_page int`, `p_page_size int` | `SETOF` → `decodeList<SupabaseWatchedItem>` | 1-based paging; deterministic `ORDER BY` (R8). |
| `sync_get_watched_items_delta_cursor` | `p_profile_id int` | scalar `bigint` → `decodeAs<Long>` | `coalesce(max(event_id),0)` — **client does not wrap this call; it must never error** (R5). |
| `sync_pull_watched_items_delta` | `p_profile_id int`, `p_since_event_id int8`, `p_limit int` | `SETOF` → `decodeList<SupabaseWatchedItemEvent>` | R1/R7/R8; zeroed `watched_at` on delete rows. |
| `sync_delete_watched_items` | `p_profile_id int`, `p_keys jsonb` | (ignored) | **`p_profile_id` is FIRST.** `p_keys` = **array of objects** `{content_id, season?, episode?}` (omitted when null); match `IS NOT DISTINCT FROM` (R6). |

### 5.3 Library — snapshot
Table `library`, model `SupabaseLibraryItem` — required non-null: `content_id,
content_type`; defaults: `name ''`, `poster_shape 'POSTER'`, `genres []`, `added_at 0`,
`profile_id 1`; `imdb_rating` is **nullable `real`/`float4`** (pushed as a double,
decoded as `Float?`); `user_id` nullable in the model but `NOT NULL` in storage (R4).

| RPC | Args (exact) | Returns / decode | Notes |
|---|---|---|---|
| `sync_push_library` | `p_items jsonb`, `p_profile_id int` | (ignored) | item = `{content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating?, genres[], addon_base_url, added_at}`. |
| `sync_pull_library` | `p_profile_id int`, `p_limit int`, `p_offset int` | `SETOF` → `decodeList<SupabaseLibraryItem>` | offset paging, page size 500; stable order (R8). |

### 5.4 Collections / home-catalog / profile-settings — JSON blobs
Tables `collections`, `home_catalog_settings`, `profile_settings_blob`. Pulls return a
`SETOF` row and the client takes `firstOrNull()`; an **empty set is valid** (client
preserves local). `settings_json` columns are **`jsonb` stored verbatim** (a stringified
blob breaks the decode).

| RPC | Args (exact) | Returns / decode | Notes |
|---|---|---|---|
| `sync_push_collections` | `p_profile_id int`, `p_collections_json jsonb` | (ignored) | `collections_json` root is a **JSON array**. |
| `sync_pull_collections` | `p_profile_id int` | `SETOF` → `decodeList<SupabaseCollectionBlob>` | returns `{profile_id, collections_json(array), updated_at}`. |
| `sync_push_home_catalog_settings` | `p_profile_id int`, `p_settings_json jsonb`, `p_platform text` | (ignored) | push platform is `"home_catalog_shared"`. Client merges remote+local **during push** (calls the pull internally), so the pull must work for the push to be correct. |
| `sync_pull_home_catalog_settings` | `p_profile_id int`, `p_platform text` | `SETOF` → `decodeList<SupabaseHomeCatalogSettingsBlob>` | client pulls **three** platforms per restore: `"home_catalog_shared"`, `"tv"`, `"mobile"`. Must return an **empty set (not error)** for an absent platform. `settings_json` is a JSON object. Key on `(user_id, profile_id, platform)`. |
| `sync_push_profile_settings_blob` | `p_profile_id int`, `p_settings_json jsonb`, `p_platform text` | (ignored) | platform `"tv"`. `settings_json` = nested `{version:int, features:{<feature>:{<key>:{type,value}}}}` — must round-trip **verbatim**; the client reads `blob["features"].jsonObject` and silently no-ops settings restore if `features` is absent. |
| `sync_pull_profile_settings_blob` | `p_profile_id int`, `p_platform text` | `SETOF` → `decodeList<SupabaseProfileSettingsBlob>` | key on `(user_id, profile_id, platform)`; this table is **separate** from home-catalog (both hold a `"tv"` row). |

### 5.5 Profiles — minimal, REQUIRED (not optional)
`StartupSyncService.kt:369` calls `profileSyncService.pullFromRemote().getOrElse { throw it }`
**un-guarded**, as the first statement of `pullBroadRemoteData`, before library/
collections/settings. **Any** error it raises (404, bad SQL, RLS, or a decode mismatch)
aborts the entire broad restore for that member; it is retried ~3× then skips all
restore. This is the single highest-risk object — treat it accordingly.

Tables `profiles`, `profile_locks`. Model `SupabaseProfile` — the **only** required
non-null field is **`profile_index int`** (note: `profile_index`, *not* `profile_id`);
everything else is defaulted/nullable. `SupabaseProfileLockState` requires
`profile_index`; `pin_enabled` defaults false, `pin_locked_until` nullable.

| RPC | Args (exact) | Returns / decode | Notes |
|---|---|---|---|
| `sync_pull_profiles` | **none** | `SETOF` → `decodeList<SupabaseProfile>` | **No args** (the earlier `p_client_max_profiles` was wrong — that belongs to push). Each returned row must carry a non-null integer column aliased **`profile_index`**. An **empty set is safe** (client keeps its local default profile, id 1, and does not block the picker). Prefer lazily returning/auto-provisioning a default `profile_index = 1` row so the server always has one. |
| `sync_pull_profile_locks` | **none** | `SETOF` → `decodeList<SupabaseProfileLockState>` | fail-soft (only affects PIN state); still must return non-null `profile_index`. |
| `sync_push_profiles` | `p_client_max_profiles int`, `p_profiles jsonb` | (ignored) | profile = `{profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins, avatar_id?, avatar_url?}`. Fired only from profile-management UI — not the startup path. |
| `sync_delete_profile_data` | `p_profile_id int` | (ignored) | UI-only; not on any startup/auth/restore path. The default profile (id 1) is client-guarded from deletion. |

`profile_id` columns on the data tables are **plain ints, NOT foreign-keyed** to
`profiles` — a never-pushed profile must not cause data pushes to violate an FK.

## 6. Client changes

**None.** The client is complete and unchanged. This preserves KevBox's
"minimal-divergence, re-apply small deltas after upstream merges" model — there is
nothing new to re-apply on the client side. (But the *server* schema now becomes a new
form of divergence; see §11.)

## 7. Deliberately excluded (clash-avoidance)

Verified safe because `StartupSyncService.pullBroadRemoteData` wraps each non-profile
sync service in its **own** try/catch (lines 389–469); a missing RPC fails soft for
that service only.

| Excluded | Reason |
|---|---|
| Addons (`addons`, `sync_push_addons`) | `member_addon` already owns addon restore. Full flavor **already bypasses** this path via `FEATURE_MEMBER_ADDON_CONFIG` (`StartupSyncService.kt:424`, `AddonSyncService.kt:43`). Deploying it would resurrect a killed path. |
| Device-linking (`linked_devices`, `generate_sync_code`, `claim_sync_code`, `get_sync_code`, `start/poll_tv_login_session`, `unlink_device`) | Collides with the one-device-per-member access model (`claim_device`/`member_device_policy`). Only invoked by an explicit "link device" UI, never on the startup path. The `get_sync_owner` collapse (§3) removes the server-side dependency. |
| Plugin sync (`plugins`, `sync_push_plugins`) | Scrapers are global/default on a family box; not member-specific. `pluginSyncService` is in a try/catch and its one `getEffectiveUserId` caller is exception-safe → fails soft. *(Revisit only if members configure their own scraper repos.)* |
| PIN / avatar RPCs (`set_profile_pin`, `verify_profile_pin`, `clear_profile_pin`, `get_avatar_catalog`) | UI-only profile management, not on the restore path. Fails soft. |
| `get_sync_overview` | **Not** purely device-linking — the Account screen's Sync panel calls it (`AccountViewModel.kt:362`). Omitting it does not break restore (fails soft), but the in-app panel will read all-zeros. **Decision:** ship a minimal owner-scoped row-count version (it doubles as the §8 detection probe), or accept the zeros and note it. |

**`get_sync_owner` is NOT excluded** — it must be deployed (§3); it is the one shared
resolver the data RPCs depend on.

**Namespace safety:** all new objects use bare upstream names (`watch_progress`,
`profiles`, `sync_*`, `get_sync_owner`, …), verified disjoint from KevBox's `member_*`
/ `kevbox_*` tables and functions against the live DB. Nothing is overwritten — except
confirm no stray `get_sync_owner` pre-exists before the `CREATE OR REPLACE`.

## 8. Rollout, gating & blast radius (~330 members)

**Trigger.** `StartupSyncService` runs on the `FullAccount` auth state — i.e. after a
session exists, including the post-login pass on a fresh install — so reinstall →
login → restore does fire. The implementation plan must confirm the broad restore is
(re)entered after a fresh-install login, not only at cold start before a session.

**Per-subsystem gating (corrects the earlier "gated only by
`shouldUseSupabaseWatchProgressSync()`" claim):**

| Subsystem | Gate | Runs for Trakt members? |
|---|---|---|
| watch_progress, watched_items | `shouldUseSupabaseWatchProgressSync()` (false when Trakt is the progress source) | No |
| library | `librarySourceMode == TRAKT && trakt authed` → skipped | No (when Trakt library) |
| collections, home_catalog_settings, profile_settings_blob, profiles | **ungated** — always run in `pullBroadRemoteData` | **Yes** |

So the "Trakt members are unaffected" safety argument holds **only** for
progress/watched/library. Collections, settings, and profiles restore for **every**
member including Trakt users — any correctness bug there reaches them too, and the
test plan must include a Trakt member (§9, T-TRAKT).

**Blast radius / canary.** Applying `*_setup.sql` flips restore **on** fleet-wide at
each member's next `FullAccount` emission; there is **no** per-member flag and the only
rollback is teardown (which discards rows written in the meantime). Mitigation
(decision for the plan): either (a) a temporary allowlist column the DEFINER RPCs check
so a canary member is enabled first, or (b) a Supabase branch DB + a single canary
build, before fleet-wide enable. At minimum, document that teardown is the only
rollback and is data-destructive.

**Data-flow on the two real scenarios:**
- **Reinstall / new TV (empty local):** pull restores from cloud — the goal. `lastSuccessfulPushMs` resets to 0; with nothing local there is nothing to clobber.
- **First start after deploy (existing local data):** the delta path pulls a snapshot and merges, **preserving** local entries newer than `lastSuccessfulPushMs`; pushes then populate the cloud. The R2 `last_watched`-guarded server upsert is what makes this safe regardless of push/pull ordering — **not** any client-side ordering guarantee.

## 9. Observability, rollback & testing

Restore failures are **silent**: every path swallows the error into a logcat-only
`Log.e` and a discarded `Result.failure` (and watch-progress further degrades a broken
delta to a snapshot). A subtly-wrong RPC therefore breaks restore for hundreds of
headless TVs invisibly. The plan must add a **detection signal** before fleet-wide
go-live.

- **Detection (pick at least one):** a temporary client telemetry event on restore-RPC
  failure (reuse the existing `record_error`); and/or a post-deploy **RPC-probe script**
  that, as a real test member, calls all 24 deployed RPCs (5 watch-progress + 5
  watched-items + 2 library + 2 collections + 2 home-catalog + 2 profile-settings +
  4 profiles + `get_sync_owner` + optional `get_sync_overview`) and asserts none return
  404/`42883`/decode-shape errors; and/or the minimal `get_sync_overview` row-count
  cross-check (§7).
- **Rollback:** `*_teardown.sql` drops only the new objects; dropping the RPCs reverts
  every member to local-only at next start, no client update. Teardown is
  data-destructive (canary-window rows are lost) — state this in the runbook.

**Testing.** SQL-level (psql assertions against the live project or a branch DB) plus a
device-level emulator restore on the `kevbox_tv` AVD, full-debug flavor:

- **T-ISO — cross-member isolation (R1):** member A's pulls/deltas return **zero** of
  member B's rows, for every subsystem (all share `profile_id = 1`).
- **T-UPSERT — conflict guard (R2):** push `position=10, last_watched=T`; push
  `position=500, last_watched=T+1`; then push the stale `position=10, last_watched=T`
  again → stored stays 500; no spurious delta event on the rejected push.
- **T-KEY — upsert keys (R3):** repeated pushes of the same `progress_key` update one
  row (no duplication, no `ON CONFLICT` error).
- **T-CURSOR — empty cursor (R5):** a zero-event member's cursor RPCs return `0`, not
  NULL (both watch-progress and watched-items).
- **T-NULLOWNER (R4):** a push with no/expired JWT inserts nothing and writes no
  NULL-`user_id` rows.
- **T-DELETE — both shapes (R6):** round-trip delete for watch-progress (string keys)
  and watched-items (object keys incl. a movie with null season/episode) — deleted rows
  do **not** resurrect on the next pull.
- **T-DECODE — return shape (R7):** a row with NULL `video_id` (watch-progress) /
  missing `profile_index` (profiles) / stringified `settings_json` would throw on the
  client; assert the RPCs never produce those.
- **T-PAGE — paging stability (R8):** drive >900 watched items / >500 library items and
  assert no dup/drop across pages.
- **T-PROFILE — un-guarded pull (§5.5):** `sync_pull_profiles()` (no args) resolves and
  decodes for a brand-new account (empty or default row), so the broad restore does not
  abort.
- **T-TRAKT — Trakt member:** with Trakt as progress source, watch_progress/
  watched_items are skipped, while collections/settings/profiles restore without
  clobbering local.
- **T-REG — live-subsystem regression:** after the shared deploy (incl.
  `get_sync_owner`), `get_access_verdict`/`claim_device`/`member_addon` apply/
  `record_heartbeat` still behave; confirm `get_sync_owner` did not pre-exist.
- **T-E2E — device restore:** on the AVD, play content → clear app data → re-login →
  confirm continue-watching, history, library, collections, and settings come back.

## 10. Deliverables

- `watch_progress_setup.sql` / `_teardown.sql`
- `watched_items_setup.sql` / `_teardown.sql`
- `sync_misc_setup.sql` / `_teardown.sql` (library, collections, home-catalog,
  profile-settings, profiles, `get_sync_owner`, optional `get_sync_overview`) — or split
  per-subsystem if cleaner.
- `prune_sync_events` retention function (R10).
- SQL test script(s) covering the T-* cases in §9.
- Post-deploy RPC-probe script (§9 detection).
- Deployment + verification runbook (mirrors existing `member_*` runbooks), including
  the canary path and the data-destructive-teardown caveat.

## 11. Maintenance — server↔client contract drift

The hand-written server schema is now a **new, invisible form of divergence**: KevBox's
usual model re-applies small *client* deltas after an upstream merge, but here the
*server* must track the upstream **client's** RPC contract, which has no representation
in this repo. A future upstream sync that changes a `postgrest.rpc(...)` name, argument
set, payload key, or `Supabase*` model field will silently break restore with no
client-side merge conflict to flag it.

**Required practice:** on every upstream merge, diff the `core/sync/*SyncService.kt`
call sites and `SupabaseModels.kt` against the deployed `*_setup.sql`, and re-run the
§9 RPC-probe. Add this step to the repo's `UPSTREAM-SYNC.md`.

## 12. Locked decisions

- Whole data-restore set, **minus** addon/plugin/device-linking/PIN/avatar.
- Profiles deployed at minimal (single default profile, `profile_index = 1`) — required
  by the un-guarded startup pull; **no-arg** `sync_pull_profiles`.
- Zero client changes; `get_sync_owner()` returns scalar `text` `= auth.uid()` and is
  behaviourally neutral for the one-device topology.
- All server RPCs honour the cross-cutting requirements R1–R10 (§4); the §5 contract is
  the authoritative signature list.
- Plugins out, PIN/avatar out, addons out (revisitable later; additive, no rework).
- Collections/settings/profiles restore for all members incl. Trakt — accepted, with
  T-TRAKT coverage.
- **SQL tests run against a disposable Supabase branch DB** (a faithful clone of prod) —
  not the live project, not Docker. The branch already has the real `auth` schema, roles,
  and the `member_*` triggers, so tests must self-isolate (per-file transaction rollback)
  and must not recreate the auth surface.
- **Go-live gating:** applying any `*_setup.sql` to the **live** project (not the branch)
  must wait until the §9 detection probe, the §8 canary path, and the §10 runbook exist
  (delivered in plan 3). A subsystem may be exercised on the branch + a single canary build
  before then, but not fleet-wide — the only rollback is data-destructive teardown.
