# Cloud Restore for KevBox TV — Design Spec

**Date:** 2026-06-14
**Branch:** `feat/watch-progress-sync`
**Status:** Approved design → ready for implementation plan
**Backend:** KevBox Supabase project `scmqdptagksltnwiveyh`

## 1. Goal

A member uninstalls/reinstalls KevBox TV, or signs in on a fresh TV. On login,
their **watch progress, watch history, library, collections, and settings are
immediately restored** from the cloud.

This is achieved with **pure `auth.uid()`-scoped storage** on the existing KevBox
Supabase project. The Android client already ships the entire sync implementation
(inherited from upstream NuvioTV); only the server-side schema is missing.

**Non-goal:** multi-device *linking* (sharing one library across several TVs via a
"sync owner"), member-controlled addon/plugin sync, and multi-profile management.
These are excluded by design (see §6).

## 2. Background / current state

- The Android client contains a complete `core/sync/` package
  (`WatchProgressSyncService`, `WatchedItemsSyncService`, `LibrarySyncService`,
  `CollectionSyncService`, `HomeCatalogSettingsSyncService`,
  `ProfileSettingsSyncService`, `ProfileSyncService`, plus addon/plugin services),
  wired into `WatchProgressRepositoryImpl`, `StartupSyncService`, and
  `AccountViewModel`. It talks to Supabase via Postgrest RPC.
- The client's Supabase project (`BuildConfig.SUPABASE_URL`) is **already the KevBox
  project** — the same one holding `member_addon`, `member_access`, and telemetry.
  No re-pointing is needed.
- The 330 members are **real Supabase Auth users**; `member_addon` RLS
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
calls `get_sync_owner` (no args, returns the owner uuid as text;
`AuthManager.kt:132`) and the server RPCs use it internally to decide whose rows to
read/write (`WatchProgressSyncService.kt:249`: *"Uses get_sync_owner() server-side
to fetch the correct user's data"*). The client passes **no** user/owner id for the
data RPCs.

KevBox is **one-device-per-member**, so sync-owner ≡ `auth.uid()`. We define a single
resolver:

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
- Bonus: this fixes the documented `getEffectiveUserId` fail-open gotcha — it now
  returns `auth.uid()` instead of a divergent owner id.

## 4. Objects to deploy

All tables are `auth.uid()`-scoped and hardened to match the **`member_addon`
pattern**: enable RLS, `select`-own policy, **revoke** the default
anon/`authenticated` write/truncate grants (writes happen only through
`security definer` RPCs), `security_invoker = on` for any helper views. Every table
carries a `profile_id int default 1` column for client fidelity.

### 4.1 Watch progress (event-sourced, delta sync)
- Tables: `watch_progress` (state), `watch_progress_events` (append-only log with a
  monotonic `event_id` sequence).
- State columns (from `SupabaseWatchProgress`): `user_id`, `content_id`,
  `content_type`, `video_id`, `season?`, `episode?`, `position`, `duration`,
  `last_watched`, `progress_key`, `profile_id`.
- Event columns (from `SupabaseWatchProgressEvent`): `event_id`, `operation`
  (`upsert`/`delete`), `progress_key`, content fields, `position`, `duration`,
  `last_watched`.
- RPCs (params reverse-engineered from `WatchProgressSyncService`):
  - `sync_push_watch_progress(p_entries jsonb)` — upsert array; appends events.
  - `sync_pull_watch_progress(p_profile_id, p_since_last_watched?)` — full snapshot.
  - `sync_get_watch_progress_delta_cursor(p_profile_id) → bigint` — latest event_id.
  - `sync_pull_watch_progress_delta(p_profile_id, p_since_event_id, p_limit)` — paged events.
  - `sync_delete_watch_progress(p_keys, p_profile_id)` — delete + append delete events.

### 4.2 Watched items (event-sourced, delta sync)
- Tables: `watched_items` (state), `watched_items_events` (log + sequence).
- RPCs (params from `WatchedItemsSyncService`): `sync_push_watched_items(p_items)`,
  `sync_pull_watched_items(p_profile_id, p_page, p_page_size)`,
  `sync_get_watched_items_delta_cursor(p_profile_id)`,
  `sync_pull_watched_items_delta(p_profile_id, p_since_event_id, p_limit)`,
  `sync_delete_watched_items(p_keys, p_profile_id)`.

### 4.3 Library (snapshot)
- Table: `library` (columns from `SupabaseLibraryItem`).
- RPCs: `sync_push_library(p_items, p_profile_id)`,
  `sync_pull_library(p_profile_id, p_limit, p_offset)`.

### 4.4 Collections / Home-catalog settings / Profile settings (JSON blobs)
- Tables: `collections`, `home_catalog_settings`, `profile_settings_blob`.
- RPCs: `sync_push_collections(p_collections_json, p_profile_id)` /
  `sync_pull_collections(p_profile_id)`;
  `sync_push_home_catalog_settings(p_settings_json, p_platform, p_profile_id)` /
  `sync_pull_home_catalog_settings(p_platform, p_profile_id)`;
  `sync_push_profile_settings_blob(p_settings_json, p_platform, p_profile_id)` /
  `sync_pull_profile_settings_blob(p_platform, p_profile_id)`.

### 4.5 Profiles (minimal — REQUIRED, not optional)
`StartupSyncService.kt:369` calls `profileSyncService.pullFromRemote().getOrElse { throw it }`
**un-guarded**, before the broad restore (library/collections/settings). If
`sync_pull_profiles` 404s, the whole broad restore aborts. We therefore deploy a
minimal single-default-profile-per-account implementation.
- Tables: `profiles`, `profile_locks`.
- RPCs deployed: `sync_pull_profiles(p_client_max_profiles)`,
  `sync_pull_profile_locks`, `sync_push_profiles(p_profiles)`,
  `sync_delete_profile_data(p_profile_id)`.
- RPCs **not** deployed (UI-only, fail-soft): `set_profile_pin`,
  `verify_profile_pin`, `clear_profile_pin`, `get_avatar_catalog`.

### 4.6 Resolver
- `get_sync_owner()` — see §3.

## 5. Client changes

**None.** The client is complete and unchanged. This preserves KevBox's
"minimal-divergence, re-apply small deltas after upstream merges" model — there is
nothing new to re-apply on the next upstream sync.

## 6. Deliberately excluded (clash-avoidance)

Verified safe because `StartupSyncService` wraps each sync service in its **own**
try/catch (lines 389–469); a missing RPC fails soft for that service only.

| Excluded | Reason |
|---|---|
| Addons (`addons`, `sync_push_addons`) | `member_addon` already owns addon restore. Full flavor **already bypasses** this path via `FEATURE_MEMBER_ADDON_CONFIG` (`StartupSyncService.kt:424`, `AddonSyncService.kt:43`). Deploying it would resurrect a killed path. |
| Device-linking (`linked_devices`, `generate_sync_code`, `claim_sync_code`, `get_sync_code`, `get_sync_overview`, `start/poll_tv_login_session`, `unlink_device`) | Collides with the one-device-per-member access model (`claim_device`/`member_device_policy`). Only invoked by an explicit "link device" UI, never on the startup path. Fails soft. The `get_sync_owner` collapse (§3) removes the server-side dependency. |
| Plugin sync (`plugins`, `sync_push_plugins`) | Scrapers are global/default on a family box; not member-specific. `pluginSyncService` is in a try/catch → fails soft. *(Revisit only if members configure their own scraper repos.)* |
| PIN / avatar RPCs | UI-only profile management, not on the restore path. Fails soft. |

**Namespace safety:** all new objects use bare upstream names (`watch_progress`,
`profiles`, …), disjoint from KevBox's `member_*` / `kevbox_*` tables (verified
against the live DB). Nothing is overwritten.

## 7. Rollout & safety (330 members)

- Sync is gated only by `shouldUseSupabaseWatchProgressSync()` — it returns false
  (Supabase sync off) for members whose progress source is Trakt; those members
  already get cross-device continuity via Trakt. For everyone else, deploying the
  RPCs turns cloud restore **on** at next app start.
- **First start after deploy** (member with existing local data): app pushes local →
  pulls (empty) → merge **preserves local** (no data loss).
- **Reinstall / new TV** (empty local): pull restores from cloud — the goal.
- **Procedure:** apply idempotent `*_setup.sql` → end-to-end emulator test with one
  throwaway test member (play content, clear app data, re-login, confirm restore) →
  then it is live for all non-Trakt members (no per-member flag).
- **Rollback:** `*_teardown.sql` drops only the newly-created objects (mirrors the
  existing `member_*_setup.sql` / `member_*_teardown.sql` convention). Dropping the
  RPCs reverts every member to local-only at next start; no client update needed.

## 8. Testing

- **SQL-level** (psql assertions, run against the live project or a branch DB):
  push→pull round-trip per subsystem; delta-cursor monotonicity for watch-progress
  and watched-items; **RLS cross-member isolation** (member A cannot read/write
  member B's rows).
- **Device-level:** emulator restore test as in §7 (`kevbox_tv` AVD, full-debug
  flavor).

## 9. Deliverables

- `watch_progress_setup.sql` / `_teardown.sql`
- `watched_items_setup.sql` / `_teardown.sql`
- `sync_misc_setup.sql` / `_teardown.sql` (library, collections, home-catalog,
  profile-settings, profiles, `get_sync_owner`) — or split per-subsystem if cleaner.
- SQL test script(s).
- Deployment + verification runbook (mirrors existing member-* runbooks).

## 10. Locked decisions

- Whole data-restore set, **minus** addon/plugin/device-linking/PIN/avatar.
- Profiles deployed at minimal (single default profile) — required by the un-guarded
  startup pull.
- Zero client changes; `get_sync_owner() = auth.uid()`.
- Plugins out, PIN/avatar out (revisitable later; additive, no rework).
