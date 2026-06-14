# Stremio → KevBox Import — Option B: Client Union-on-First-Pull Patch (Kotlin)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan modifies the Android client** (`~/projects/NuvioTV`) — all steps run there, NOT in the importer repo. Verification is **compile + R8 + on-device/emulator** (the merge logic is extracted into pure JVM-testable helpers so the core is unit-tested).

**Goal:** Eliminate the §9.2/§9.3 silent-loss class **fleet-wide** by making the device's **first** restore pull a **UNION** of local + remote (instead of a destructive REPLACE) for `watched_items` and `library`. This removes the fragile operator sequencing of corrected Option A and is the **only** thing that protects a member's KevBox-only **library** on first restore. **Mandated before fleet-wide C1** (spec §9.4 decision: do BOTH A and B).

**Spec:** `/home/kevin/projects/NuvioTV/docs/superpowers/specs/2026-06-14-stremio-kevbox-direct-import-design.md` (rev 4, §9.2/§9.3/§9.4).
**Companion plan:** `2026-06-14-stremio-kevbox-import-core.md` (Plan 1 — the TS importer + corrected Option A). Option B makes Plan 1's `--no-library` workaround unnecessary once shipped.

## Background — exactly what's broken (verified against ground truth)

`watch_progress` is **already safe** — `WatchProgressPreferences` preserves local entries via an unconditional non-Trakt-id check plus `lastWatched > lastSuccessfulPushMs` (obs 4549). **No change there.** The two destructive paths:

1. **`watched_items` — `WatchedItemsPreferences.replaceWithRemoteItems`** (`:222-261`). The preserve block is gated behind `if (lastSuccessfulPushMs > 0L)` (`:242`). A never-synced device has `lastSuccessfulPushMs == 0` → the block is skipped → local is fully replaced by the remote snapshot (`:255`). Reached only on the true first pull (`!deltaInitialized` snapshot path, `WatchedItemsSyncService.kt:244-265`, which passes `lastSuccessfulPushMs` at `:251`). After the snapshot, `preservedLocalItems==true` triggers `watchedItemsSyncService.pushToRemote()` (`StartupSyncService.kt:505-512`) which calls `markPushSucceeded()` (`WatchedItemsSyncService.kt:121-130,162-163`).

2. **`library` — `LibraryPreferences.mergeRemoteItems`** (`:109-124`). Rebuilds local purely from `dedupedRemote` (`:116-122`); the only guard is empty-remote → keep local (`:112-114`). **No `lastSuccessfulPushMs` concept exists.** **rev 4 — TWO never-synced first-pull callers, not one** (verified via grep): `StartupSyncService.kt:392` (cold-start libraryJob) **and `AccountViewModel.kt:629`** (`pullRemoteData`, the sign-in / sync-code-claim / QR-login restore path). Both pass the single-arg signature today → both REPLACE. **The `hasCompletedInitialPull` flag is NOT a usable first-pull signal** (audit-confirmed): it is an in-memory `@Singleton var` (`LibraryRepositoryImpl.kt:60`, resets every process launch) **and is set `true` by the Trakt-mode branches WITHOUT any merge** (`StartupSyncService.kt:289,297,340`), so `!hasCompletedInitialPull` mis-fires (a later warm/foreground pull, or a Trakt→Local switch, reads it `true` and falls back to destructive REPLACE). The only library push path is `LibraryRepositoryImpl.triggerRemoteSync()` (`:62-72`, gated on `hasCompletedInitialPull`, fires on a user library mutation) — **there is no forced push after the library merge** (contrast watched_items' `preservedLocalItems → pushToRemote`).

**Fix shape:**
- **`watched_items`:** the snapshot restore path opts into UNION on the first pull (never-synced) via a new explicit `unionWhenNeverSynced=true` arg; the existing `preservedLocalItems → push` path then writes the union to cloud and sets `lastSuccessfulPushMs`. All other callers (notably `TraktViewModel.repopulateWatchedItemsFromNuvioSync`, `:425`) keep the default `false` → byte-identical to today's pure replace.
- **`library`:** UNION-ALWAYS on both restore callsites (`preserveLocal=true` unconditionally — we do **not** read the unreliable `hasCompletedInitialPull`), which is safe because the cloud library upsert **never deletes** (`library_setup.sql:33,56`): in steady state local ⊆ cloud so union ≡ replace, and the only case where they differ is a local-only saved title — exactly what we must protect. After a merge that preserved any local-only item, **force `librarySyncService.pushToRemote()`** (mirrors watched_items) so the union becomes durable in cloud and self-heals on every pull — closing the device-swap / app-data-clear loss that union-on-device alone leaves open.

---

## File Structure

In `/home/kevin/projects/NuvioTV` (`app/src/main/java/com/nuvio/tv/`):

| File | Change | New? |
|---|---|---|
| `data/local/SyncMergeLogic.kt` | Pure, JVM-testable `unionWatchedSnapshot(...)` (returns `Pair<list, preserved>`) + `unionLibrarySnapshot(...)` (returns `Pair<list, preserved>`) | new |
| `app/src/test/java/com/nuvio/tv/data/local/SyncMergeLogicTest.kt` | JUnit unit tests for both helpers | new |
| `data/local/WatchedItemsPreferences.kt` | `replaceWithRemoteItems` gains `unionWhenNeverSynced: Boolean = false`, calls `unionWatchedSnapshot` (union only when never-synced **and** opted in) | modify |
| `core/sync/WatchedItemsSyncService.kt` | snapshot restore call (`:249`) passes `unionWhenNeverSynced = true` (the only opt-in caller) | modify |
| `data/local/LibraryPreferences.kt` | `mergeRemoteItems` gains `preserveLocal` param, **returns `Boolean` (preserved)**, calls `unionLibrarySnapshot` | modify |
| `core/sync/StartupSyncService.kt` | cold-start libraryJob (`:392`): `mergeRemoteItems(remote, preserveLocal = true)` → if preserved, `librarySyncService.pushToRemote()` (forced push) | modify |
| `ui/screens/account/AccountViewModel.kt` | `pullRemoteData` library branch (`:629`): same union-always + forced-push edit (the sign-in/QR-restore first-pull path) | modify |

`WatchProgressPreferences.kt` is **untouched** (already non-destructive). `librarySyncService`/`libraryRepository` are already injected into both `StartupSyncService` and `AccountViewModel` (existing call sites), so the forced push needs no new wiring.

---

## Task 0: Branch + confirm anchors + test source set

**Files:** none (recon).

- [ ] **Step 1: Confirm the working branch.** Client work ships from `kevbox` via `./release.sh`. Confirm whether to land Option B on the current `feat/watch-progress-sync` branch (then merge to `kevbox` for release) or directly on a fresh branch off `kevbox`. Run `git -C /home/kevin/projects/NuvioTV branch --show-current` and `git -C /home/kevin/projects/NuvioTV log --oneline -3`. **Operator decision** — default: implement on the current sync branch, merge to `kevbox` at release time (mirrors how cloud-restore is staged).

- [ ] **Step 2: Re-confirm ALL the line anchors** (they may have shifted) — `grep -n` each before editing:
  - `replaceWithRemoteItems` preserve guard in `WatchedItemsPreferences.kt` (~`:242`); its snapshot caller in `WatchedItemsSyncService.kt` (~`:249`); the **other** `replaceWithRemoteItems` caller `TraktViewModel.repopulateWatchedItemsFromNuvioSync` (~`:425`, must stay pure-replace).
  - `mergeRemoteItems` in `LibraryPreferences.kt` (~`:109`); its **two** callers — `StartupSyncService.kt` libraryJob (~`:392`) **and `AccountViewModel.pullRemoteData` (~`:629`)** — and note `StartupSyncService` sets `libraryRepository.hasCompletedInitialPull = true` at `:289,297,340,393,397,402` (the `:289/:297/:340` ones are Trakt branches with NO merge — which is why we do **not** gate union on this flag). Edit against the CURRENT text, not the line numbers above.

- [ ] **Step 3: Confirm the JVM unit-test source set + deps already exist** (recon only — no edit expected): `ls /home/kevin/projects/NuvioTV/app/src/test/java/com/nuvio/tv/data/local/` (already holds e.g. `PlayerSettingsTimeoutPredicateTest.kt`) and `grep -n "testImplementation" app/build.gradle.kts`. As of this writing `app/build.gradle.kts` (Kotlin DSL — note `.kts`, there is **no** Groovy `app/build.gradle`) already declares `testImplementation("junit:junit:4.13.2")` (`:480`), `kotlinx-coroutines-test` (`:481`), and `mockk` (`:482`), and the `app/src/test/java` source set exists — so **no dependency or source-set edit is needed**. The pure helpers are plain Kotlin (no Android/Robolectric). Only add JUnit if the grep unexpectedly comes back empty.

- [ ] **Step 4: Confirm the data-class shapes** the helpers + test fixtures depend on. Run `grep -n "data class WatchedItem" -r app/src/main` and `grep -n "data class SavedLibraryItem" -r app/src/main` and read both declarations.
  - `WatchedItem` (`domain/model/WatchedItem.kt`): `contentId: String, contentType: String, title: String, season: Int? = null, episode: Int? = null, watchedAt: Long` — the plan's `wi(...)` fixture matches.
  - `SavedLibraryItem` (`domain/model/SavedLibraryItem.kt`): has **11 REQUIRED ctor args** — `id, type, name, poster: String?, posterShape: PosterShape, background: String?, description: String?, releaseInfo: String?, imdbRating: Float?, genres: List<String>, addonBaseUrl: String?` — only `logo`/`addedAt` default. **`posterShape` is the enum `PosterShape` (values `POSTER`/`LANDSCAPE`/`SQUARE`, `domain/model/PosterShape.kt`), NOT a String.** The plan's `li(id, type)` fixture (Task 1) must supply all 11 (use `posterShape = PosterShape.POSTER`, `null` for the nullable String/Float fields, `emptyList()` for genres) — the bare `SavedLibraryItem(id, type)` form will NOT compile.

---

## Task 1: Pure union helpers (TDD)

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/data/local/SyncMergeLogic.kt`
- Create: `app/src/test/java/com/nuvio/tv/data/local/SyncMergeLogicTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/nuvio/tv/data/local/SyncMergeLogicTest.kt`. **Note the domain-model imports** — the test is in package `com.nuvio.tv.data.local` but `WatchedItem`/`SavedLibraryItem`/`PosterShape` live in `com.nuvio.tv.domain.model`, so they MUST be imported or the file won't compile (re-confirm the exact `SavedLibraryItem` signature from Task 0 Step 4):
```kotlin
package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncMergeLogicTest {
    private fun wi(id: String, s: Int?, e: Int?, at: Long) =
        WatchedItem(contentId = id, contentType = "series", title = id, season = s, episode = e, watchedAt = at)

    @Test fun `never-synced unions ALL local not in remote when opted in`() {
        val current = listOf(wi("a", 1, 1, 10), wi("b", 1, 2, 20))
        val remote = listOf(wi("a", 1, 1, 99)) // remote wins for overlap
        val (merged, preserved) = unionWatchedSnapshot(current, remote, lastSuccessfulPushMs = 0L, unionWhenNeverSynced = true)
        assertTrue(preserved)
        // a from remote (99), b preserved from local (20)
        assertEquals(setOf("a" to 99L, "b" to 20L), merged.map { it.contentId to it.watchedAt }.toSet())
    }

    @Test fun `never-synced WITHOUT opt-in preserves nothing (today's pure replace, e_g_ TraktViewModel)`() {
        val current = listOf(wi("a", 1, 1, 10), wi("b", 1, 2, 20))
        val remote = listOf(wi("a", 1, 1, 99))
        val (merged, preserved) = unionWatchedSnapshot(current, remote, lastSuccessfulPushMs = 0L, unionWhenNeverSynced = false)
        assertFalse(preserved)
        // only remote survives — identical to today's gated-off (lastSuccessfulPushMs == 0) replace
        assertEquals(setOf("a" to 99L), merged.map { it.contentId to it.watchedAt }.toSet())
    }

    @Test fun `synced keeps only local newer than last push (opt-in irrelevant)`() {
        val current = listOf(wi("b", 1, 2, 20), wi("c", 1, 3, 200))
        val remote = listOf(wi("a", 1, 1, 50))
        val (merged, preserved) = unionWatchedSnapshot(current, remote, lastSuccessfulPushMs = 100L, unionWhenNeverSynced = true)
        assertTrue(preserved)
        // a (remote) + c (200 > 100); b (20 <= 100) dropped — existing synced behavior preserved
        assertEquals(setOf("a", "c"), merged.map { it.contentId }.toSet())
    }

    @Test fun `remote-only when no local`() {
        val (merged, preserved) = unionWatchedSnapshot(emptyList(), listOf(wi("a", 1, 1, 1)), 0L, unionWhenNeverSynced = true)
        assertFalse(preserved)
        assertEquals(listOf("a"), merged.map { it.contentId })
    }

    private fun li(id: String, type: String) = SavedLibraryItem(
        id = id, type = type, name = id, poster = null,
        posterShape = PosterShape.POSTER, background = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null,
    )

    @Test fun `library union preserves local-only when preserveLocal`() {
        val current = listOf(li("x", "movie"), li("y", "series"))
        val remote = listOf(li("x", "MOVIE")) // case-insensitive type key
        val (merged, preserved) = unionLibrarySnapshot(current, remote, preserveLocal = true)
        assertTrue(preserved)
        assertEquals(setOf("x", "y"), merged.map { it.id }.toSet())
    }

    @Test fun `library replace (no preserve) drops local-only`() {
        val current = listOf(li("x", "movie"), li("y", "series"))
        val remote = listOf(li("x", "movie"))
        val (merged, preserved) = unionLibrarySnapshot(current, remote, preserveLocal = false)
        assertFalse(preserved)
        assertEquals(listOf("x"), merged.map { it.id })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /home/kevin/projects/NuvioTV && ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.data.local.SyncMergeLogicTest"` (use the actual full-flavor unit-test task; `./gradlew tasks | grep UnitTest` if unsure).
Expected: FAIL — `unionWatchedSnapshot` / `unionLibrarySnapshot` unresolved.

- [ ] **Step 3: Implement the pure helpers**

Create `app/src/main/java/com/nuvio/tv/data/local/SyncMergeLogic.kt`:
```kotlin
package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem

/**
 * rev 4 Option B — pure, side-effect-free merge helpers extracted from the *Preferences classes so the
 * union-on-first-pull logic is JVM-unit-testable (no DataStore / gson / Android).
 */

/**
 * First-pull-aware snapshot merge for watched_items.
 * @param lastSuccessfulPushMs > 0 ⇒ device has pushed ⇒ keep today's behavior (preserve only local watched
 *        after the last push). <= 0 ⇒ never pushed.
 * @param unionWhenNeverSynced when never-synced, true ⇒ UNION all local not in remote (the restore-path fix);
 *        false ⇒ preserve NOTHING (today's pure replace — used by every non-restore caller, e.g. TraktViewModel).
 * @return (merged list, whether any local item was preserved — drives the post-pull push).
 */
fun unionWatchedSnapshot(
    current: List<WatchedItem>,
    remote: List<WatchedItem>,
    lastSuccessfulPushMs: Long,
    unionWhenNeverSynced: Boolean,
): Pair<List<WatchedItem>, Boolean> {
    val deduped = LinkedHashMap<Triple<String, Int?, Int?>, WatchedItem>()
    remote.forEach { deduped[Triple(it.contentId, it.season, it.episode)] = it } // remote wins on overlap
    val neverSynced = lastSuccessfulPushMs <= 0L
    var preserved = false
    current.forEach { local ->
        val key = Triple(local.contentId, local.season, local.episode)
        if (key !in deduped) {
            val keep = if (neverSynced) unionWhenNeverSynced else local.watchedAt > lastSuccessfulPushMs
            if (keep) {
                deduped[key] = local
                preserved = true
            }
        }
    }
    return deduped.values.toList() to preserved
}

/**
 * Snapshot merge for library.
 * @param preserveLocal true ⇒ UNION local-only saved titles with remote (the restore-path fix; safe to apply
 *        on every pull because the cloud library upsert never deletes — local ⊆ cloud in steady state, so union
 *        ≡ replace except for not-yet-pushed local-only titles); false ⇒ replace (remote only).
 * @return (merged list, whether any local-only item was preserved — drives the forced post-merge push).
 */
fun unionLibrarySnapshot(
    current: List<SavedLibraryItem>,
    remote: List<SavedLibraryItem>,
    preserveLocal: Boolean,
): Pair<List<SavedLibraryItem>, Boolean> {
    val deduped = LinkedHashMap<Pair<String, String>, SavedLibraryItem>()
    remote.forEach { deduped[it.id to it.type.lowercase()] = it }
    var preserved = false
    if (preserveLocal) {
        current.forEach { local ->
            val key = local.id to local.type.lowercase()
            if (key !in deduped) {
                deduped[key] = local
                preserved = true
            }
        }
    }
    return deduped.values.toList() to preserved
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /home/kevin/projects/NuvioTV && ./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.data.local.SyncMergeLogicTest"`
Expected: PASS, 6 tests (4 watched incl. the never-synced opt-in/opt-out pair, 2 library).

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/NuvioTV
git add app/src/main/java/com/nuvio/tv/data/local/SyncMergeLogic.kt app/src/test/java/com/nuvio/tv/data/local/SyncMergeLogicTest.kt
git commit -m "feat(sync): pure union-on-first-pull merge helpers (Option B) + unit tests"
```

---

## Task 2: Wire watched_items to the union helper

**Files:** Modify `app/src/main/java/com/nuvio/tv/data/local/WatchedItemsPreferences.kt`

- [ ] **Step 1: Add the opt-in param + replace the gated preserve block in `replaceWithRemoteItems`**

First add the new param to the method signature (keep the others + the `return preservedLocalItems` at the end):
```kotlin
    suspend fun replaceWithRemoteItems(
        remoteItems: List<WatchedItem>,
        lastSuccessfulPushMs: Long = 0L,
        profileId: Int = profileManager.activeProfileId.value,
        unionWhenNeverSynced: Boolean = false, // rev 4 Option B — only the restore snapshot path opts in; default keeps pure replace
    ): Boolean {
```
Then in the `store(profileId).edit { ... }` body, after the empty-remote guard at `:231-234`, replace the manual dedup + the `if (lastSuccessfulPushMs > 0L) { ... }` preserve block (`:235-254`) with a call to the pure helper:
```kotlin
            val localItems = current.mapNotNull { json ->
                runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            }
            // rev 4 Option B — union all local not in remote ONLY when never-synced AND the caller opted in
            // (the restore snapshot path). Synced devices keep the newer-than-push rule; other callers (default
            // unionWhenNeverSynced=false) keep today's pure replace.
            val (merged, preserved) = unionWatchedSnapshot(localItems, remoteItems, lastSuccessfulPushMs, unionWhenNeverSynced)
            preservedLocalItems = preserved
            preferences[watchedItemsKey] = merged.map { gson.toJson(it) }.toSet()
            Log.d(TAG, "replaceWithRemoteItems: profile=$profileId stored=${merged.size} preservedLocal=$preservedLocalItems (unionWhenNeverSynced=$unionWhenNeverSynced neverSynced=${lastSuccessfulPushMs <= 0L})")
```
(Delete the now-unused `deduped` LinkedHashMap and the old preserve loop. `SyncMergeLogic.kt` is in the same `com.nuvio.tv.data.local` package, so no import is needed for `unionWatchedSnapshot`.)

- [ ] **Step 2: Opt the snapshot restore path into the union**

In `WatchedItemsSyncService.kt` the only first-pull restore caller is the `!deltaInitialized` snapshot branch (~`:249-253`). Add the opt-in arg:
```kotlin
                val hadUnsyncedItems = watchedItemsPreferences.replaceWithRemoteItems(
                    remoteWatchedItems,
                    lastSuccessfulPushMs = lastSuccessfulPushMs,
                    profileId = profileId,
                    unionWhenNeverSynced = true, // rev 4 Option B — first restore pull unions local watched marks
                )
```
**Do NOT touch `TraktViewModel.repopulateWatchedItemsFromNuvioSync` (`:425`)** — it calls `replaceWithRemoteItems(remoteItems)` with the default (`unionWhenNeverSynced=false`), so it stays byte-identical to today's pure replace (verified the only other caller via grep).

- [ ] **Step 3: Type-check / compile**

Run: `cd /home/kevin/projects/NuvioTV && ./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

Run:
```bash
cd /home/kevin/projects/NuvioTV
git add app/src/main/java/com/nuvio/tv/data/local/WatchedItemsPreferences.kt app/src/main/java/com/nuvio/tv/core/sync/WatchedItemsSyncService.kt
git commit -m "feat(sync): watched_items unions local on first restore pull (Option B) — no silent loss"
```

---

## Task 3: Wire library to the union helper (union-always + forced push) — BOTH first-pull callsites

**Files:**
- Modify `app/src/main/java/com/nuvio/tv/data/local/LibraryPreferences.kt`
- Modify `app/src/main/java/com/nuvio/tv/core/sync/StartupSyncService.kt`
- Modify `app/src/main/java/com/nuvio/tv/ui/screens/account/AccountViewModel.kt`

> **Design (rev 4):** we do **NOT** gate the library union on `hasCompletedInitialPull` — it is an in-memory flag that the Trakt branches set `true` without merging (`StartupSyncService.kt:289,297,340`), so `!hasCompletedInitialPull` would mis-fire into a destructive REPLACE. Instead both restore callsites pass `preserveLocal = true` **unconditionally** (union-always). This is safe because the cloud library upsert never deletes (`library_setup.sql:33,56`): in steady state local ⊆ cloud so union ≡ replace, and the only divergence is a not-yet-pushed local-only title — exactly what we protect. To make that protection **durable** (survive app-data-clear / reinstall / device swap), `mergeRemoteItems` now returns whether it preserved any local-only item, and each callsite **forces `librarySyncService.pushToRemote()`** when it did (mirrors the watched_items `preservedLocalItems → push` pattern). The push uses the no-delete upsert, so it is purely additive and self-heals on every pull.

- [ ] **Step 1: Add `preserveLocal`, return `Boolean`, use the helper**

Replace `LibraryPreferences.mergeRemoteItems` (`:109-124`) with:
```kotlin
    suspend fun mergeRemoteItems(
        remoteItems: List<SavedLibraryItem>,
        preserveLocal: Boolean = false, // rev 4 Option B — true on restore paths → union local-only saved titles, not replace
    ): Boolean {
        var preservedLocalItems = false
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "mergeRemoteItems: remote list empty while local has ${current.size} entries; preserving local library")
                return@edit
            }
            val localItems = current.mapNotNull { json ->
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }.getOrNull()
            }
            val (merged, preserved) = unionLibrarySnapshot(localItems, remoteItems, preserveLocal)
            preservedLocalItems = preserved
            preferences[libraryItemsKey] = merged.map { gson.toJson(it) }.toSet()
            Log.d(TAG, "mergeRemoteItems: stored=${merged.size} preserveLocal=$preserveLocal preserved=$preserved local=${localItems.size} remote=${remoteItems.size}")
        }
        return preservedLocalItems
    }
```
The default `preserveLocal = false` keeps any future caller's behavior identical (replace); only the two restore callsites opt into union. (`unionLibrarySnapshot` is same-package — no import needed.)

- [ ] **Step 2: Cold-start path — `StartupSyncService.libraryJob` (union-always + forced push)**

In `StartupSyncService.kt` (the `libraryJob`, ~`:390-394`):
```kotlin
                        val remoteLibraryItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                        Log.d(TAG, "Pulled ${remoteLibraryItems.size} library items from remote")
                        val preservedLocalLibrary = libraryPreferences.mergeRemoteItems(
                            remoteLibraryItems,
                            preserveLocal = true, // rev 4 Option B — union local-only saved titles (safe: cloud upsert is no-delete)
                        )
                        libraryRepository.hasCompletedInitialPull = true
                        if (preservedLocalLibrary) {
                            Log.d(TAG, "Detected preserved local library items, pushing union to remote")
                            librarySyncService.pushToRemote()
                        }
                        Log.d(TAG, "Reconciled local library with ${remoteLibraryItems.size} remote items")
```

- [ ] **Step 3: Sign-in / QR-restore path — `AccountViewModel.pullRemoteData` (the SECOND first-pull callsite)**

In `AccountViewModel.kt` (`pullRemoteData`, the `!isTraktConnected` branch, ~`:626-635`), replace the single-arg merge in the `onSuccess` block:
```kotlin
                    onSuccess = { remoteLibraryItems ->
                        Log.d("AccountViewModel", "pullRemoteData: pulled ${remoteLibraryItems.size} library items")
                        val preservedLocalLibrary = libraryPreferences.mergeRemoteItems(
                            remoteLibraryItems,
                            preserveLocal = true, // rev 4 Option B — sign-in/QR restore is a first pull; union, never replace
                        )
                        libraryRepository.hasCompletedInitialPull = true // align with StartupSyncService; enables later mutation pushes
                        if (preservedLocalLibrary) {
                            Log.d("AccountViewModel", "pullRemoteData: detected preserved local library items, pushing union to remote")
                            librarySyncService.pushToRemote()
                        }
                        Log.d("AccountViewModel", "pullRemoteData: reconciled local library with ${remoteLibraryItems.size} remote items")
                    },
```
(`librarySyncService` and `libraryRepository` are already injected — used at `AccountViewModel.kt:579/:626`. Do NOT touch the watched_items branch at `:638-643`: it routes through `syncDeltaFromRemote → replaceWithRemoteItems` and inherits the Task 2 fix automatically.)

- [ ] **Step 4: Compile (full flavor) + R8**

The KevBox build ships the **`full`** flavor only (the `playstore` flavor is never built — see build.gradle.kts).

Run: `cd /home/kevin/projects/NuvioTV && ./gradlew :app:compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Run:
```bash
cd /home/kevin/projects/NuvioTV
git add app/src/main/java/com/nuvio/tv/data/local/LibraryPreferences.kt app/src/main/java/com/nuvio/tv/core/sync/StartupSyncService.kt app/src/main/java/com/nuvio/tv/ui/screens/account/AccountViewModel.kt
git commit -m "feat(sync): library unions local on first restore pull + forced push (Option B) — durable KevBox-only saved titles"
```

---

## Task 4: Build + on-device/emulator verification

**Files:** none (verification).

- [ ] **Step 1: Full release-config build (R8) for the shipping flavor**

Run the same build `./release.sh` uses (e.g. `./gradlew :app:assembleFullRelease` or the documented release task). Expected: R8-clean, no missing-keep warnings on `SyncMergeLogic`.

- [ ] **Step 2: Emulator merge-regression test** (per the emulator workflow in operator memory — `kevbox_tv` AVD, `-gpu host` + `HardwareDecoder=off`):
  1. Install the patched debug build on a fresh profile (never-synced: clear app data).
  2. Locally mark a watched item + save a library title that are NOT in the cloud snapshot for that member.
  3. Trigger a startup sync (cold start). Confirm via logcat: `replaceWithRemoteItems: ... unionWhenNeverSynced=true neverSynced=true` and `mergeRemoteItems: ... preserveLocal=true preserved=true`.
  4. Assert the local-only watched mark AND saved title **survive** alongside the pulled remote items (union), and that `watched_items` subsequently pushes (logcat `Detected unsynced watched items, pushing to remote`).
  5. **Library durability (cloud-arrival) — closes the §9.3 gap.** Confirm the forced library push fired: logcat `Detected preserved local library items, pushing union to remote` followed by `Pushed N library items to remote for profile <id>` (`LibrarySyncService.kt:78` — match the `for profile` suffix, not a bare "Pushed N library items"), with N including the previously-local-only title. Then **clear app data again (or reinstall) and trigger a fresh startup pull**: assert the previously-local-only saved title is now restored **FROM CLOUD** (present after the re-pull with no local copy beforehand). This proves durability, not just same-session on-device survival.

- [ ] **Step 3: Canary on-device verification (real TV, real merge-sensitive member) — BOTH restore paths**

This is the §10 step that corrected Option A could only do for watch_progress + watched_items. With Option B installed, repeat the canary for a library-bearing merge-sensitive member WITHOUT `--no-library`:
  1. Import (Plan 1 `--commit`, library included) → on the TV confirm prior KevBox **saved library** survives + Stremio titles added, nothing dropped.
  2. **Durability:** do one library mutation (or clear-data + restore) and confirm the prior KevBox saved titles are now durable in cloud (re-pull restores them with no local copy). This is the gating proof Option B fixes §9.3.
  3. **Exercise the sign-in/QR-restore path too**, not just cold-start StartupSync — sign out and back in (or claim a sync code) on a never-synced device with a local-only saved title and confirm it survives + pushes (the `AccountViewModel.pullRemoteData` callsite from Task 3 Step 3).

---

## Task 5: Release (operator-run)

**Files:** none (operator action — code-only plan delivers up to the commits).

- [ ] **Step 1:** Merge the Option B commits into `kevbox` (per Task 0 Step 1 branch decision).
- [ ] **Step 2:** `./release.sh` (signs with the release keystore — every APK MUST use it or updates break) → publishes to `tv.kevbox.dev`. **GOTCHA (operator memory):** if `release.sh` SSH-to-persovps fails AFTER the signed build, do NOT re-run (double-bumps versionCode) — resume publish manually with the built APK.
- [ ] **Step 3:** Sideload to the real TVs (32-bit `armeabi-v7a`; the emulator is 64-bit). **Only after Option B is live on a member's TV may library be imported for that member** (drop `--no-library`).
- [ ] **Step 4:** Update spec §17 step 5 / the Plan-1 runbook to record Option B as shipped; re-enable C1 planning (Plan 2) which was gated on Option B.

---

## Self-Review (completed during planning)

**Spec coverage:** §9.2 (watched_items REPLACE) → Task 2 (both `replaceWithRemoteItems` callers covered: snapshot path opts into union, TraktViewModel stays pure-replace); §9.3 (library REPLACE, unprotectable by sequencing) → Task 3 (BOTH first-pull callsites — `StartupSyncService.kt:392` AND `AccountViewModel.kt:629` — union-always + forced push for cloud durability); §9.4 Option B "union when never-synced" → Tasks 1-3; canary library survival + durability (the thing Option A's `--no-library` defers) → Task 4 Step 2.5/Step 3; release + sideload → Task 5. **Untouched (already safe):** `watch_progress` (obs 4549).

**Surgical-ness:**
- *watched_items:* behavior changes only on the **first restore pull of a never-synced device** (snapshot path passes `unionWhenNeverSynced=true`, `lastSuccessfulPushMs <= 0` → union). Synced devices (`lastSuccessfulPushMs > 0` → newer-than-push rule) and the only other caller, `TraktViewModel.repopulateWatchedItemsFromNuvioSync` (default `unionWhenNeverSynced=false` → pure replace), are **byte-identical** to today.
- *library:* `preserveLocal=true` (union-always) on both restore callsites differs from today's replace **only** when a not-yet-pushed local-only saved title exists — the case §9.3 exists to protect. In steady state (local ⊆ cloud, guaranteed by the no-delete upsert + the forced push) union ≡ replace. Removal semantics are unchanged (the cloud never deletes, so a removed-but-still-in-cloud title is re-hydrated by both replace and union). We deliberately do **not** read the unreliable `hasCompletedInitialPull`.
- No RPC/schema/contract change; no new sync surface. Library reuses the existing `sync_push_library` upsert.

**Testability:** the correctness-bearing merge logic is extracted into pure JVM functions (`SyncMergeLogic.kt`, both returning `(merged, preserved)`) and unit-tested (Task 1, 6 tests) — the DataStore `.edit{}` wrappers become thin adapters. Remaining risk (DataStore wiring, startup/sign-in ordering, forced-push cloud arrival) is covered by compile + emulator + on-device (Task 4, incl. the clear-data/restore durability check).

**Resolved during folding (rev-4 audit):** `WatchedItem`/`SavedLibraryItem`/`PosterShape` signatures confirmed (Task 0 Step 4; `li()` fixture + imports corrected); `hasCompletedInitialPull` is in-memory + Trakt-polluted → **not used** as the union signal (union-always instead); the shipping flavor is `full` only (no `playstore`/`foss` compile). **Open (operator, Task 0 Step 1):** branch strategy (sync branch → `kevbox`).
