# Daily Background Update Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a once-a-day WorkManager background check (full flavor) that pre-downloads + SHA-256-verifies a newer APK on unmetered networks, so the existing update dialog offers an instant Install on next app open.

**Architecture:** A plain `CoroutineWorker` (no `@HiltWorker`) resolves its dependencies through a Hilt `@EntryPoint`, keeping the whole feature inside `app/src/full/`. All decision logic — the download gate, the cache-match predicate, the prune selection, the asset-URL trust check, and the ViewModel's resolve — is extracted into pure, unit-tested functions; the Android-coupled plumbing is verified by build + an emulator smoke test on a **release** build. WorkManager is added only to the `full` flavor.

**Tech Stack:** Kotlin, WorkManager (`androidx.work:work-runtime-ktx`), Hilt, DataStore Preferences, kotlinx.serialization, JUnit4 (pure-JVM tests; no mocking needed).

**Spec:** `docs/superpowers/specs/2026-06-11-daily-background-update-check-design.md`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `gradle/libs.versions.toml` | WorkManager version + library alias | Modify |
| `app/build.gradle.kts` | `fullImplementation` WorkManager | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt` | Pure worker logic: download gate + `isCachedFor` + `staleFileNames` + `isTrustedAssetUrl` | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt` | Pure ViewModel resolve logic | Create |
| `app/src/full/java/com/nuvio/tv/updater/Checksum.kt` | Correct the integrity-vs-authenticity doc comment | Modify |
| `app/src/full/java/com/nuvio/tv/updater/ApkDownloader.kt` | Atomic `.part`-then-rename write (no partial file ever at the final path) | Modify |
| `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt` | Make `AppUpdate` `@Serializable` | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt` | Shared `Json` instance | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdatePreferences.kt` | +2 pre-download keys | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateWorkerEntryPoint.kt` | Hilt EntryPoint for the worker | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt` | The daily worker | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdateWorkScheduler.kt` | Enqueue periodic + one-time work | Create |
| `app/src/full/java/com/nuvio/tv/core/runtime/PluginRuntimeHooks.kt` | Schedule on app start (full only) | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt` | Merged resolve + instant install + cache cleanup | Modify |
| `app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt` | Tests for the pure worker logic | Create |
| `app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt` | Tests for resolve | Create |

> **`app/src/testFull/` is a new, flavor-scoped unit-test source set** (AGP recognizes it implicitly). The new tests must NOT go in the shared `app/src/test/`: `UpdateResolution`/`UpdateDownloadDecision`/`AppUpdate` exist only in the `full` flavor, so putting them in `app/src/test/` would break `testPlaystoreDebugUnitTest`.

---

### Task 1: Add WorkManager dependency to the full flavor

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts` (dependencies block, near the other `add("fullImplementation", …)` calls ~line 440-443)

- [ ] **Step 1: Add the version**

In `gradle/libs.versions.toml`, under `[versions]` (next to `datastore = "1.1.1"`), add:

```toml
workManager = "2.10.0"
```

(compileSdk 36 / minSdk 24 / AGP 8.13.2 satisfy WorkManager 2.10's requirements — verified.)

- [ ] **Step 2: Add the library alias**

In `gradle/libs.versions.toml`, under `[libraries]` (next to the `datastore-preferences` line), add:

```toml
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
```

- [ ] **Step 3: Wire it into the full flavor only**

In `app/build.gradle.kts`, in the `dependencies { … }` block alongside the existing `add("fullImplementation", libs.jsoup)` / `add("fullImplementation", libs.nicehttp)` lines, add:

```kotlin
add("fullImplementation", libs.androidx.work.runtime)
```

- [ ] **Step 4: Verify it resolves**

Run: `./gradlew :app:dependencies --configuration fullDebugRuntimeClasspath | grep work-runtime`
Expected: a line showing `androidx.work:work-runtime-ktx:2.10.0`.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(updater): add WorkManager to the full flavor"
```

---

### Task 2: `UpdateDownloadDecision` — pure worker logic (TDD)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt`

This object holds **all** the worker's pure decisions: the download gate (`shouldDownload`), the shared cache-match predicate (`isCachedFor`, also used by `UpdateResolution`), the prune selection (`staleFileNames`), and the asset-URL trust gate (`isTrustedAssetUrl`).

- [ ] **Step 1: Write the failing test**

Create `app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt`:

```kotlin
package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadDecisionTest {

    private fun decide(
        remote: Int = 1030,
        current: Int = 1028,
        ignored: Int? = null,
        alreadyCached: Boolean = false,
        unmetered: Boolean = true,
    ) = UpdateDownloadDecision.shouldDownload(remote, current, ignored, alreadyCached, unmetered)

    // --- shouldDownload ---

    @Test
    fun `downloads when newer, not ignored, not cached, on unmetered`() {
        assertTrue(decide())
    }

    @Test
    fun `skips when remote equals current`() {
        assertFalse(decide(remote = 1028, current = 1028))
    }

    @Test
    fun `skips when remote is older than current`() {
        assertFalse(decide(remote = 1027, current = 1028))
    }

    @Test
    fun `skips when this version is ignored`() {
        assertFalse(decide(remote = 1030, ignored = 1030))
    }

    @Test
    fun `downloads when a different version is ignored`() {
        assertTrue(decide(remote = 1030, ignored = 1029))
    }

    @Test
    fun `skips when already cached for this version`() {
        assertFalse(decide(alreadyCached = true))
    }

    @Test
    fun `skips when network is metered`() {
        assertFalse(decide(unmetered = false))
    }

    // --- isCachedFor ---

    @Test
    fun `isCachedFor true when version matches and file exists`() {
        assertTrue(UpdateDownloadDecision.isCachedFor(1030, 1030, cachedFileExists = true))
    }

    @Test
    fun `isCachedFor false when version differs`() {
        assertFalse(UpdateDownloadDecision.isCachedFor(1029, 1030, cachedFileExists = true))
    }

    @Test
    fun `isCachedFor false when file missing`() {
        assertFalse(UpdateDownloadDecision.isCachedFor(1030, 1030, cachedFileExists = false))
    }

    @Test
    fun `isCachedFor false when no cached version`() {
        assertFalse(UpdateDownloadDecision.isCachedFor(null, 1030, cachedFileExists = true))
    }

    // --- staleFileNames ---

    @Test
    fun `staleFileNames returns everything except the kept name`() {
        assertEquals(
            listOf("old-1.apk", "old-2.apk"),
            UpdateDownloadDecision.staleFileNames(listOf("old-1.apk", "keep.apk", "old-2.apk"), "keep.apk"),
        )
    }

    @Test
    fun `staleFileNames with null keep returns everything`() {
        assertEquals(
            listOf("a.apk", "b.apk"),
            UpdateDownloadDecision.staleFileNames(listOf("a.apk", "b.apk"), null),
        )
    }

    // --- isTrustedAssetUrl ---

    @Test
    fun `isTrustedAssetUrl trusts https on the configured host`() {
        assertTrue(UpdateDownloadDecision.isTrustedAssetUrl("https://tv.kevbox.dev/k.apk", "https://tv.kevbox.dev"))
    }

    @Test
    fun `isTrustedAssetUrl rejects http on the configured host`() {
        assertFalse(UpdateDownloadDecision.isTrustedAssetUrl("http://tv.kevbox.dev/k.apk", "https://tv.kevbox.dev"))
    }

    @Test
    fun `isTrustedAssetUrl rejects https on a different host`() {
        assertFalse(UpdateDownloadDecision.isTrustedAssetUrl("https://evil.example/k.apk", "https://tv.kevbox.dev"))
    }

    @Test
    fun `isTrustedAssetUrl rejects a malformed url`() {
        assertFalse(UpdateDownloadDecision.isTrustedAssetUrl("not a url", "https://tv.kevbox.dev"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testFullDebugUnitTest --tests "com.nuvio.tv.updater.UpdateDownloadDecisionTest"`
Expected: FAIL — `UpdateDownloadDecision` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt`:

```kotlin
package com.nuvio.tv.updater

import java.net.URI

/**
 * Pure decisions for the background [UpdateCheckWorker], extracted so they can be unit-tested
 * without Android APIs. [isCachedFor] is also reused by [UpdateResolution] so the worker's
 * "already cached?" and the ViewModel's "installable?" never drift.
 */
object UpdateDownloadDecision {

    /** Whether the worker should pre-download an APK for [remoteVersionCode]. */
    fun shouldDownload(
        remoteVersionCode: Int,
        currentVersionCode: Int,
        ignoredVersionCode: Int?,
        alreadyCachedForRemote: Boolean,
        isUnmetered: Boolean,
    ): Boolean =
        remoteVersionCode > currentVersionCode &&
            ignoredVersionCode != remoteVersionCode &&
            !alreadyCachedForRemote &&
            isUnmetered

    /** True when a previously cached pre-download still satisfies [targetVersionCode]. */
    fun isCachedFor(
        cachedVersionCode: Int?,
        targetVersionCode: Int,
        cachedFileExists: Boolean,
    ): Boolean =
        cachedVersionCode != null && cachedVersionCode == targetVersionCode && cachedFileExists

    /** Names in [allNames] to prune — everything except [keepName] (null keeps nothing). */
    fun staleFileNames(allNames: List<String>, keepName: String?): List<String> =
        allNames.filter { it != keepName }

    /** Only download an asset served over https from the same host as [baseUrl]. */
    fun isTrustedAssetUrl(assetUrl: String, baseUrl: String): Boolean {
        val asset = runCatching { URI(assetUrl) }.getOrNull() ?: return false
        if (!"https".equals(asset.scheme, ignoreCase = true)) return false
        val assetHost = asset.host ?: return false
        val baseHost = runCatching { URI(baseUrl) }.getOrNull()?.host ?: return false
        return assetHost.equals(baseHost, ignoreCase = true)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testFullDebugUnitTest --tests "com.nuvio.tv.updater.UpdateDownloadDecisionTest"`
Expected: PASS (17 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt
git commit -m "feat(updater): pure worker decisions (download gate, cache-match, prune, url-trust)"
```

---

### Task 3: `UpdateResolution` — pure resolve logic (TDD)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt`

Note: this task uses the existing `AppUpdate` data class (no serialization needed yet) and reuses `UpdateDownloadDecision.isCachedFor` from Task 2.

- [ ] **Step 1: Write the failing test**

Create `app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt`:

```kotlin
package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateResolutionTest {

    private fun update(versionCode: Int) = AppUpdate(
        versionCode = versionCode,
        tag = "v$versionCode",
        title = "Title",
        notes = "notes",
        sha256 = "abc",
        releaseUrl = null,
        assetName = "kevbox-$versionCode.apk",
        assetUrl = "https://tv.kevbox.dev/kevbox-$versionCode.apk",
        assetSizeBytes = null,
    )

    @Test
    fun `live newer with matching cached apk is installable`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030),
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = true,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertEquals(1030, d.update?.versionCode)
        assertTrue(d.isUpdateAvailable)
        assertTrue(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `live newer than cached apk is not installable (download on demand)`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1031),
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = true,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertEquals(1031, d.update?.versionCode)
        assertNull(d.installableApkPath)
    }

    @Test
    fun `offline falls back to cached metadata and stays installable`() {
        val d = UpdateResolution.resolve(
            liveUpdate = null,
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = true,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertEquals(1030, d.update?.versionCode)
        assertTrue(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `cached apk file missing is not installable`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030),
            cachedUpdate = update(1030),
            cachedApkPath = "/cache/u.apk",
            cachedApkExists = false,
            currentVersionCode = 1028,
            ignoredVersionCode = null,
            force = false,
        )
        assertNull(d.installableApkPath)
    }

    @Test
    fun `ignored version is available but does not auto-show`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = false,
        )
        assertTrue(d.isUpdateAvailable)
        assertFalse(d.showDialog)
    }

    @Test
    fun `ignored version with a matching cached apk stays hidden but is still installable`() {
        // Decided behavior: the dialog is hidden (ignored, no force), but the cached path is
        // still resolved so a later force-check can offer instant Install.
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = update(1030), cachedApkPath = "/cache/u.apk",
            cachedApkExists = true, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = false,
        )
        assertFalse(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `force offers instant install of an ignored, pre-downloaded version`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = update(1030), cachedApkPath = "/cache/u.apk",
            cachedApkExists = true, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = true,
        )
        assertTrue(d.showDialog)
        assertEquals("/cache/u.apk", d.installableApkPath)
    }

    @Test
    fun `force shows the dialog even for an ignored version`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1030), cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = 1030,
            force = true,
        )
        assertTrue(d.showDialog)
    }

    @Test
    fun `no update available when not newer`() {
        val d = UpdateResolution.resolve(
            liveUpdate = update(1028), cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = null,
            force = false,
        )
        assertFalse(d.isUpdateAvailable)
        assertFalse(d.showDialog)
        assertNull(d.installableApkPath)
    }

    @Test
    fun `nothing live and nothing cached yields empty decision`() {
        val d = UpdateResolution.resolve(
            liveUpdate = null, cachedUpdate = null, cachedApkPath = null,
            cachedApkExists = false, currentVersionCode = 1028, ignoredVersionCode = null,
            force = true,
        )
        assertNull(d.update)
        assertFalse(d.isUpdateAvailable)
        assertTrue(d.showDialog) // force surfaces the error dialog
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testFullDebugUnitTest --tests "com.nuvio.tv.updater.UpdateResolutionTest"`
Expected: FAIL — `UpdateResolution` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt`:

```kotlin
package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate

/**
 * Pure resolve logic for [UpdateViewModel]: merges the live check result with the cached
 * background pre-download. Live wins; cached metadata is the offline fallback. A cached APK
 * is only "installable" when its file exists and its versionCode matches the effective
 * update — the same [UpdateDownloadDecision.isCachedFor] predicate the worker uses. The
 * installable path is computed independent of the ignore flag; the dialog is gated by
 * [Decision.showDialog], so an ignored-but-pre-downloaded version stays hidden until a force
 * check (then it correctly offers instant Install).
 */
object UpdateResolution {

    data class Decision(
        val update: AppUpdate?,
        val isUpdateAvailable: Boolean,
        val showDialog: Boolean,
        val installableApkPath: String?,
    )

    fun resolve(
        liveUpdate: AppUpdate?,
        cachedUpdate: AppUpdate?,
        cachedApkPath: String?,
        cachedApkExists: Boolean,
        currentVersionCode: Int,
        ignoredVersionCode: Int?,
        force: Boolean,
    ): Decision {
        val effective = liveUpdate ?: cachedUpdate
            ?: return Decision(update = null, isUpdateAvailable = false, showDialog = force, installableApkPath = null)

        val remoteNewer = effective.versionCode > currentVersionCode
        val notIgnored = ignoredVersionCode == null || ignoredVersionCode != effective.versionCode
        val installable = cachedApkPath?.takeIf {
            UpdateDownloadDecision.isCachedFor(cachedUpdate?.versionCode, effective.versionCode, cachedApkExists)
        }

        return Decision(
            update = effective,
            isUpdateAvailable = remoteNewer,
            showDialog = (remoteNewer && notIgnored) || force,
            installableApkPath = installable,
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testFullDebugUnitTest --tests "com.nuvio.tv.updater.UpdateResolutionTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt
git commit -m "feat(updater): pure resolve logic merging live check with cached pre-download"
```

---

### Task 4: Make `AppUpdate` serializable, add `UpdateJson`, correct the `Checksum` comment

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt`
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/Checksum.kt`

- [ ] **Step 1: Annotate `AppUpdate`**

In `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt`, the existing declaration is:

```kotlin
@Keep
data class AppUpdate(
```

Change to (add `@Serializable`; `import kotlinx.serialization.Serializable` is already present in this file for `UpdateManifest`):

```kotlin
@Keep
@Serializable
data class AppUpdate(
```

- [ ] **Step 2: Create the shared Json holder**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt`:

```kotlin
package com.nuvio.tv.updater

import kotlinx.serialization.json.Json

/** Shared JSON config for persisting/restoring the cached pre-download [model.AppUpdate]. */
internal object UpdateJson {
    val json: Json = Json { ignoreUnknownKeys = true }
}
```

- [ ] **Step 3: Correct the misleading security comment in `Checksum.kt`**

The current doc comment overstates the guarantee. Replace:

```kotlin
/**
 * SHA-256 verification for downloaded APKs (ported from kevbox-support `Checksum`).
 * A compromised/misconfigured host cannot push an APK that doesn't match the manifest hash.
 */
```

with:

```kotlin
/**
 * SHA-256 verification for downloaded APKs (ported from kevbox-support `Checksum`).
 *
 * This is an INTEGRITY check (guards against a corrupted/truncated download), NOT authenticity:
 * the hash comes from the same host as the APK (over the app's trust-all OkHttpClient), so a
 * forged manifest can supply a matching hash. Authenticity is enforced at install time by
 * Android's signing-certificate match against the installed app (the release keystore).
 * See the spec's Security section.
 */
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt app/src/full/java/com/nuvio/tv/updater/Checksum.kt
git commit -m "feat(updater): serializable AppUpdate + shared Json; correct checksum security note"
```

---

### Task 5: Extend `UpdatePreferences` with pre-download keys

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdatePreferences.kt`

- [ ] **Step 1: Add the import for string keys**

In the imports block, add (next to `intPreferencesKey`):

```kotlin
import androidx.datastore.preferences.core.stringPreferencesKey
```

- [ ] **Step 2: Add keys, flows, and setters**

After the existing `setLastCheckAtMs` (i.e. inside the class, before the closing brace), add:

```kotlin
    private val predownloadApkPathKey = stringPreferencesKey("predownloaded_apk_path")
    private val predownloadUpdateJsonKey = stringPreferencesKey("predownloaded_update_json")

    /** Absolute path of a background-downloaded, SHA-256-verified APK (or null). */
    val predownloadApkPath: Flow<String?> = dataStore.data.map { prefs ->
        prefs[predownloadApkPathKey]
    }

    /** Serialized [com.nuvio.tv.updater.model.AppUpdate] matching [predownloadApkPath] (or null). */
    val predownloadUpdateJson: Flow<String?> = dataStore.data.map { prefs ->
        prefs[predownloadUpdateJsonKey]
    }

    suspend fun setPredownload(apkPath: String, updateJson: String) {
        dataStore.edit { prefs ->
            prefs[predownloadApkPathKey] = apkPath
            prefs[predownloadUpdateJsonKey] = updateJson
        }
    }

    /** Clears the pre-download pointers. The caller deletes the on-disk APK (no file handle here). */
    suspend fun clearPredownload() {
        dataStore.edit { prefs ->
            prefs.remove(predownloadApkPathKey)
            prefs.remove(predownloadUpdateJsonKey)
        }
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdatePreferences.kt
git commit -m "feat(updater): persist background pre-download path + metadata"
```

---

### Task 6: Hilt `@EntryPoint` for the worker

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateWorkerEntryPoint.kt`

- [ ] **Step 1: Create the EntryPoint**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateWorkerEntryPoint.kt`:

```kotlin
package com.nuvio.tv.updater

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets the non-injected [UpdateCheckWorker] (a plain CoroutineWorker) pull its singleton
 * dependencies from the app's Hilt graph via EntryPointAccessors.fromApplication(...).
 * This avoids @HiltWorker + Configuration.Provider wiring in the shared Application.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateWorkerEntryPoint {
    fun updateRepository(): UpdateRepository
    fun apkDownloader(): ApkDownloader
    fun updatePreferences(): UpdatePreferences
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateWorkerEntryPoint.kt
git commit -m "feat(updater): Hilt entry point for the background worker"
```

---

### Task 7: `ApkDownloader` atomic write + `UpdateCheckWorker` — the daily worker

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/ApkDownloader.kt`
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt`

- [ ] **Step 1: Make `ApkDownloader` write atomically (`.part` then rename)**

Both the worker and the foreground `UpdateViewModel.downloadUpdate` compute the **same** destination filename in `cacheDir/updates`. Today `ApkDownloader` deletes-then-streams in place, so a worker run overlapping a manual download (or the worker's prune) can corrupt or delete a half-written file. Fix it once, for both paths: stream into a `<name>.part` temp and atomically rename on success, so a partial file is never visible at the final path. In `ApkDownloader.kt`, replace the body of `download(...)` (the `runCatching { … }` block) with:

```kotlin
        return runCatching {
            destinationFile.parentFile?.mkdirs()

            // Stream into a .part temp, then atomically rename, so a partial/concurrent write is
            // never visible at the final path (and the worker's prune skips .part files).
            val partFile = File(destinationFile.parentFile, destinationFile.name + ".part")
            if (partFile.exists()) partFile.delete()

            val request = Request.Builder()
                .url(url)
                .build()

            // KevBox: APKs are 80–150 MB. The shared client has a 30s read timeout (fine for API
            // calls); relax read/write/call timeouts to unlimited for the large streamed download
            // so a slow family TV link doesn't abort it. connectTimeout stays inherited (fail fast
            // on a dead host).
            val downloadClient = okHttpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }
            }

            if (destinationFile.exists()) destinationFile.delete()
            if (!partFile.renameTo(destinationFile)) {
                // Cross-device or rename refusal: fall back to copy+delete.
                partFile.copyTo(destinationFile, overwrite = true)
                partFile.delete()
            }

            destinationFile
        }
```

- [ ] **Step 2: Create the worker**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt`:

```kotlin
package com.nuvio.tv.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Once-a-day background check. If a newer, non-ignored build exists on a trusted (https +
 * configured-host) URL and the device is on an unmetered network, downloads + SHA-256-verifies
 * the APK and caches its path + metadata so [UpdateViewModel] can offer an instant Install on
 * next app open. No notifications. Pruning runs on every invocation, keeping at most the one
 * APK still worth installing. Logs at decision points so a release smoke test can follow it.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Mirror the launch-time gate: never auto-act in debug/benchmark builds. The worker only
        // does real work in a release build (BuildConfig.IS_DEBUG_BUILD == false).
        if (BuildConfig.IS_DEBUG_BUILD) return Result.success()

        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            UpdateWorkerEntryPoint::class.java,
        )
        val repo = entry.updateRepository()
        val downloader = entry.apkDownloader()
        val prefs = entry.updatePreferences()

        val update = repo.getLatestUpdate().getOrElse {
            Log.w(TAG, "Update check failed; will retry: ${it.message}")
            return Result.retry()
        }
        prefs.setLastCheckAtMs(System.currentTimeMillis())

        val dir = File(applicationContext.cacheDir, "updates")
        val ignored = prefs.ignoredVersionCode.first()
        val cachedPath = prefs.predownloadApkPath.first()
        val cachedVersion = prefs.predownloadUpdateJson.first()
            ?.let { runCatching { UpdateJson.json.decodeFromString<AppUpdate>(it) }.getOrNull()?.versionCode }
        val cachedFileExists = cachedPath != null && File(cachedPath).exists()
        val alreadyCached = UpdateDownloadDecision.isCachedFor(cachedVersion, update.versionCode, cachedFileExists)

        // Keep an already-valid pre-download only while it is still an upgrade over the installed
        // build; otherwise nothing is worth keeping (prune clears everything).
        val targetIsUpgrade = update.versionCode > BuildConfig.VERSION_CODE
        var keepName: String? =
            if (targetIsUpgrade && alreadyCached && cachedPath != null) File(cachedPath).name else null

        val trusted = UpdateDownloadDecision.isTrustedAssetUrl(update.assetUrl, BuildConfig.UPDATE_BASE_URL)
        if (!trusted) Log.w(TAG, "Untrusted asset URL, skipping download: ${update.assetUrl}")

        val shouldDownload = trusted && UpdateDownloadDecision.shouldDownload(
            remoteVersionCode = update.versionCode,
            currentVersionCode = BuildConfig.VERSION_CODE,
            ignoredVersionCode = ignored,
            alreadyCachedForRemote = alreadyCached,
            isUnmetered = isUnmetered(applicationContext),
        )

        if (shouldDownload) {
            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(dir, safeName)

            val file = downloader.download(update.assetUrl, dest) { _, _ -> }.getOrElse {
                Log.w(TAG, "Download failed; will retry: ${it.message}")
                pruneStale(dir, keepName)
                return Result.retry()
            }

            val verified = runCatching { Checksum.verify(file, update.sha256) }.getOrDefault(false)
            if (!verified) {
                Log.w(TAG, "SHA-256 mismatch for ${file.name}; deleting and retrying")
                runCatching { file.delete() }
                pruneStale(dir, keepName)
                return Result.retry()
            }

            prefs.setPredownload(file.absolutePath, UpdateJson.json.encodeToString(update))
            keepName = file.name
            Log.i(TAG, "Pre-downloaded + verified ${file.name} (versionCode ${update.versionCode})")
        } else {
            Log.i(
                TAG,
                "No download this run (trusted=$trusted, alreadyCached=$alreadyCached, " +
                    "remote=${update.versionCode}, current=${BuildConfig.VERSION_CODE})",
            )
        }

        pruneStale(dir, keepName)
        return Result.success()
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Keep only [keepName]; delete other finalized APKs. Skips in-progress `.part` files so a
     * concurrent foreground download (ApkDownloader streams to `<name>.part` then renames) is
     * never deleted mid-write.
     */
    private fun pruneStale(dir: File, keepName: String?) {
        val names = dir.listFiles()?.filterNot { it.name.endsWith(".part") }?.map { it.name } ?: return
        UpdateDownloadDecision.staleFileNames(names, keepName).forEach { name ->
            runCatching { File(dir, name).delete() }
        }
    }

    companion object {
        const val UNIQUE_NAME = "kevbox-daily-update-check"
        const val UNIQUE_NAME_ONESHOT = "kevbox-update-check-now"
        private const val TAG = "UpdateCheckWorker"
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/ApkDownloader.kt app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt
git commit -m "feat(updater): atomic APK write + daily CoroutineWorker (trusted pre-download, verify, prune)"
```

---

### Task 8: `UpdateWorkScheduler` — enqueue periodic + one-time work

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateWorkScheduler.kt`

- [ ] **Step 1: Create the scheduler**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateWorkScheduler.kt`:

```kotlin
package com.nuvio.tv.updater

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Schedules the once-a-day [UpdateCheckWorker] plus a prompt one-time kick. Idempotent — safe to
 * call on every app start. The CONNECTED constraint gates only the cheap version check; the
 * worker itself enforces unmetered-only for the actual APK download.
 */
object UpdateWorkScheduler {

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val wm = WorkManager.getInstance(context)

        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        // UPDATE (not KEEP): a future change to the period/constraints/backoff propagates to
        // already-installed TVs instead of being frozen at the first-ever enqueue. An unchanged
        // request is a no-op (no reschedule), so this stays safe to call on every app start.
        wm.enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        // PeriodicWork's first run is deferred up to the interval (~24h). Kick a one-time run so a
        // freshly set-up TV pre-downloads promptly. KEEP = at most one in flight; the worker's own
        // debug/trust/metered/already-cached gates still apply.
        val initial = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        wm.enqueueUniqueWork(
            UpdateCheckWorker.UNIQUE_NAME_ONESHOT,
            ExistingWorkPolicy.KEEP,
            initial,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateWorkScheduler.kt
git commit -m "feat(updater): schedule daily + prompt one-time update-check work"
```

---

### Task 9: Schedule on app start (full-only seam)

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/core/runtime/PluginRuntimeHooks.kt:20-27` (the `onApplicationCreate` function)

- [ ] **Step 1: Add the scheduling call**

In `app/src/full/java/com/nuvio/tv/core/runtime/PluginRuntimeHooks.kt`, the existing function is:

```kotlin
    fun onApplicationCreate(application: Application) {
        // Defer heavy Conscrypt + baseClient init until a cloudstream extension is
        // actually invoked (player launch / source/plugin screens). On cold start
        // for users who never open those screens, this saves ~50-200ms of native
        // crypto provider setup on the main thread.
        this.application = application
        AcraApplication.context = application
    }
```

Append the scheduling call (wrapped so it can never crash app start) so it becomes:

```kotlin
    fun onApplicationCreate(application: Application) {
        // Defer heavy Conscrypt + baseClient init until a cloudstream extension is
        // actually invoked (player launch / source/plugin screens). On cold start
        // for users who never open those screens, this saves ~50-200ms of native
        // crypto provider setup on the main thread.
        this.application = application
        AcraApplication.context = application

        // KevBox TV: register the once-a-day background update check (full flavor only).
        // Idempotent (UPDATE periodic + KEEP one-time), best-effort so a WorkManager hiccup
        // never blocks startup.
        try {
            com.nuvio.tv.updater.UpdateWorkScheduler.ensureScheduled(application)
        } catch (t: Throwable) {
            Log.w("NuvioApplication", "Failed to schedule update check: ${t.message}")
        }
    }
```

(`Log` is already imported in this file.)

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/core/runtime/PluginRuntimeHooks.kt
git commit -m "feat(updater): schedule daily check from the full app-start hook"
```

---

### Task 10: Integrate the cached pre-download into `UpdateViewModel`

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt:54-91` (`checkForUpdates`)

This replaces the body of `checkForUpdates` with the merged resolve. The public signature and the `init` call are unchanged, so the About-screen manual check (`force = true`) keeps working.

- [ ] **Step 1: Add imports**

At the top of `UpdateViewModel.kt`, add:

```kotlin
import com.nuvio.tv.updater.model.AppUpdate
import kotlinx.serialization.decodeFromString
```

(`java.io.File` and `kotlinx.coroutines.flow.first` are already imported.)

- [ ] **Step 2: Replace `checkForUpdates`**

Replace the entire existing `checkForUpdates(force, showNoUpdateFeedback)` function (lines 54-91) with:

```kotlin
    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, showNoUpdateToastHint = false) }

            val ignoredVersionCode = updatePreferences.ignoredVersionCode.first()
            val cachedApkPath = updatePreferences.predownloadApkPath.first()
            val cachedUpdate = updatePreferences.predownloadUpdateJson.first()
                ?.let { runCatching { UpdateJson.json.decodeFromString<AppUpdate>(it) }.getOrNull() }

            val live = updateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            val decision = UpdateResolution.resolve(
                liveUpdate = live.getOrNull(),
                cachedUpdate = cachedUpdate,
                cachedApkPath = cachedApkPath,
                cachedApkExists = cachedApkPath != null && File(cachedApkPath).exists(),
                currentVersionCode = BuildConfig.VERSION_CODE,
                ignoredVersionCode = ignoredVersionCode,
                force = force,
            )

            // Drop a now-installed/stale cached pre-download (file + pointer) so it can't resurface.
            if (!decision.isUpdateAvailable && cachedApkPath != null) {
                runCatching { File(cachedApkPath).delete() }
                updatePreferences.clearPredownload()
            }

            if (decision.update == null) {
                // Nothing live, nothing cached.
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        showDialog = force,
                        errorMessage = if (force) {
                            live.exceptionOrNull()?.message ?: context.getString(R.string.update_error_check_failed)
                        } else {
                            it.errorMessage
                        },
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isChecking = false,
                    update = decision.update,
                    isUpdateAvailable = decision.isUpdateAvailable,
                    downloadedApkPath = decision.installableApkPath,
                    downloadProgress = if (decision.installableApkPath != null) 1f else null,
                    showDialog = decision.showDialog,
                    showNoUpdateToastHint = showNoUpdateFeedback && !decision.isUpdateAvailable,
                    errorMessage = null,
                )
            }
        }
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm the dialog's Install path (resolved — no change expected)**

The dialog already keys the Install action off `state.downloadedApkPath != null` (`UpdatePromptDialog.kt:468`), so the `installableApkPath → downloadedApkPath` mapping surfaces **Install** on next open. No change needed. (Cosmetic only: a pre-existing 700 ms anti-double-click debounce at `UpdatePromptDialog.kt:104-120` briefly disables the button when the dialog opens, so "instant" Install arms ~0.7 s late.)

- [ ] **Step 5: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt
git commit -m "feat(updater): surface cached background pre-download as instant install"
```

---

### Task 11: Full verification (both flavors)

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew testFullDebugUnitTest`
Expected: PASS, including `UpdateDownloadDecisionTest` (17) and `UpdateResolutionTest` (10).

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew assembleFullDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build the playstore flavor (must be untouched / still compiles)**

Run: `./gradlew assemblePlaystoreDebug`
Expected: BUILD SUCCESSFUL — confirms WorkManager + the worker are not referenced by the playstore flavor.

- [ ] **Step 4: R8 sanity on the full release**

Run: `./gradlew assembleFullRelease`
Expected: BUILD SUCCESSFUL with no R8 errors. The newly-`@Serializable` `AppUpdate` is kept by the **existing** `-keep class com.nuvio.tv.updater.model.** { *; }` + `**$$serializer` rules in `app/proguard-rules.pro` (the same rules that protect `UpdateManifest`), not by `@Keep` alone. `UpdateCheckWorker` (package `com.nuvio.tv.updater`, outside `.model`) is kept by `work-runtime`'s bundled consumer rule (`* extends androidx.work.ListenableWorker` + its constructor). (`assembleFullRelease` signs with the KevBox release keystore from `local.properties` — already present.)

- [ ] **Step 5: Emulator/device smoke test (manual — RELEASE build)**

The worker returns `Result.success()` immediately when `BuildConfig.IS_DEBUG_BUILD` is true, which it is for **both `debug` and `benchmark`** (`app/build.gradle.kts:186,245`). **Only a `fullRelease` build exercises the real path** — do not smoke-test a debug APK for the download/cache behavior.

1. Serve a `version.json` at `UPDATE_BASE_URL` (`https://tv.kevbox.dev`) whose `versionCode` is **newer** than the build you install (or build a release at a lower `versionCode`).
2. Build + install the release: `./gradlew assembleFullRelease` then `adb install -r app/build/outputs/apk/full/release/*.apk` (installed applicationId is **`tv.kevbox`**, not `com.nuvio.tv` — that's the Gradle namespace).
3. Launch the app. `PluginRuntimeHooks.onApplicationCreate` enqueues the periodic work **and** a one-time kick, so on an unmetered network the worker runs within ~seconds/minutes.
4. Observe via logcat (release builds aren't `run-as`-inspectable): `adb logcat -s UpdateCheckWorker` — expect `Pre-downloaded + verified <name> (versionCode …)`. To inspect the scheduled jobs: `adb shell dumpsys jobscheduler | grep tv.kevbox`.
5. Behavioral confirm: force-close and reopen the app → the update dialog offers **Install** immediately (no download spinner), proving the cached pre-download surfaced through `UpdateResolution` + the ViewModel mapping. (If logcat shows `No download this run (... alreadyCached=false ...)` with `trusted=false`, the manifest URL host/scheme is wrong; if it never downloads on the expected network, you're on a metered link.)

- [ ] **Step 6: Final commit (if any verification fixups were needed)**

```bash
git add -A
git commit -m "test(updater): verify daily update check across full + playstore flavors"
```

---

## Self-Review Notes (author)

- **Spec coverage:** WorkManager dep (T1), pure worker logic incl. cache-match/prune/url-trust (T2), resolve logic (T3), serialization + checksum-comment fix (T4), prefs (T5), EntryPoint (T6), worker with trust gate + unconditional prune + logging (T7), scheduler with UPDATE periodic + one-time kick (T8), full-only seam (T9), ViewModel surfacing + non-destructive failure + stale file+pointer clear (T10), both-flavor build + R8 + **release** smoke test (T11). All spec sections mapped.
- **Type consistency:** `UpdateDownloadDecision.{shouldDownload, isCachedFor, staleFileNames, isTrustedAssetUrl}`, `UpdateResolution.resolve(...)` / `Decision(update, isUpdateAvailable, showDialog, installableApkPath)`, `UpdatePreferences.{setPredownload, clearPredownload, predownloadApkPath, predownloadUpdateJson}`, `UpdateWorkerEntryPoint.{updateRepository, apkDownloader, updatePreferences}`, `UpdateCheckWorker.{UNIQUE_NAME, UNIQUE_NAME_ONESHOT}`, `UpdateWorkScheduler.ensureScheduled`, `BuildConfig.UPDATE_BASE_URL` — names used identically across tasks and verified against source.
- **Catalog accessor:** TOML alias `androidx-work-runtime` → Gradle accessor `libs.androidx.work.runtime` (same dash→dot rule as `androidx-core-ktx` → `libs.androidx.core.ktx`).
- **Shared predicate:** the worker's `alreadyCached` and `UpdateResolution`'s `installableApkPath` both go through `UpdateDownloadDecision.isCachedFor`, so the two cannot drift.
- **Gaps closed vs. v1:** release-build smoke test (debug gate no-op), worker decisions extracted + tested, unconditional prune (spec↔code reconciled), ViewModel deletes the stale APK file, asset-URL https+host trust gate, security framing corrected (keystore signature = authenticity), `UPDATE` periodic policy, one-time day-1 kick, correct `tv.kevbox` applicationId + `dumpsys`/logcat trigger, atomic `.part` APK write (worker↔foreground race), MockK dropped, `testFull` source-set note, R8 keep rationale corrected.
