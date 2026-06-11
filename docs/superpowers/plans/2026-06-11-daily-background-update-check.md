# Daily Background Update Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a once-a-day WorkManager background check (full flavor) that pre-downloads + SHA-256-verifies a newer APK on unmetered networks, so the existing update dialog offers an instant Install on next app open.

**Architecture:** A plain `CoroutineWorker` (no `@HiltWorker`) resolves its dependencies through a Hilt `@EntryPoint`, keeping the whole feature inside `app/src/full/`. The download gate and the ViewModel's resolve logic are extracted into pure, unit-tested functions; the Android-coupled plumbing is verified by build + emulator. WorkManager is added only to the `full` flavor.

**Tech Stack:** Kotlin, WorkManager (`androidx.work:work-runtime-ktx`), Hilt, DataStore Preferences, kotlinx.serialization, JUnit4 + MockK (pure-JVM tests).

**Spec:** `docs/superpowers/specs/2026-06-11-daily-background-update-check-design.md`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `gradle/libs.versions.toml` | WorkManager version + library alias | Modify |
| `app/build.gradle.kts` | `fullImplementation` WorkManager | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt` | Pure worker download gate | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt` | Pure ViewModel resolve logic | Create |
| `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt` | Make `AppUpdate` `@Serializable` | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt` | Shared `Json` instance | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdatePreferences.kt` | +2 pre-download keys | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateWorkerEntryPoint.kt` | Hilt EntryPoint for the worker | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt` | The daily worker | Create |
| `app/src/full/java/com/nuvio/tv/updater/UpdateWorkScheduler.kt` | Enqueue the periodic work | Create |
| `app/src/full/java/com/nuvio/tv/core/runtime/PluginRuntimeHooks.kt` | Schedule on app start (full only) | Modify |
| `app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt` | Merged resolve + instant install | Modify |
| `app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt` | Tests for the gate | Create |
| `app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt` | Tests for resolve | Create |

---

### Task 1: Add WorkManager dependency to the full flavor

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts` (dependencies block, near the other `add("fullImplementation", …)` calls ~line 439-443)

- [ ] **Step 1: Add the version**

In `gradle/libs.versions.toml`, under `[versions]` (next to `datastore = "1.1.1"`), add:

```toml
workManager = "2.10.0"
```

- [ ] **Step 2: Add the library alias**

In `gradle/libs.versions.toml`, under `[libraries]` (next to the `datastore-preferences` line), add:

```toml
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
```

- [ ] **Step 3: Wire it into the full flavor only**

In `app/build.gradle.kts`, in the `dependencies { … }` block alongside the existing `add("fullImplementation", …)` lines, add:

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

### Task 2: `UpdateDownloadDecision` — pure download gate (TDD)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt`:

```kotlin
package com.nuvio.tv.updater

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

    @Test
    fun `downloads when newer, not ignored, not cached, on unmetered`() {
        assertTrue(decide())
    }

    @Test
    fun `skips when remote is not newer`() {
        assertFalse(decide(remote = 1028, current = 1028))
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testFullDebugUnitTest --tests "com.nuvio.tv.updater.UpdateDownloadDecisionTest"`
Expected: FAIL — `UpdateDownloadDecision` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt`:

```kotlin
package com.nuvio.tv.updater

/**
 * Pure decision for whether the background worker should pre-download an APK.
 * Extracted from [UpdateCheckWorker] so it can be unit-tested without Android APIs.
 */
object UpdateDownloadDecision {
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
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testFullDebugUnitTest --tests "com.nuvio.tv.updater.UpdateDownloadDecisionTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt app/src/testFull/java/com/nuvio/tv/updater/UpdateDownloadDecisionTest.kt
git commit -m "feat(updater): pure download gate for the background check"
```

---

### Task 3: `UpdateResolution` — pure resolve logic (TDD)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt`

Note: this task uses the existing `AppUpdate` data class (no serialization needed yet).

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
 * update (so a newer live release than what was pre-downloaded falls back to download).
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
            cachedApkExists && cachedUpdate?.versionCode == effective.versionCode
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
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateResolution.kt app/src/testFull/java/com/nuvio/tv/updater/UpdateResolutionTest.kt
git commit -m "feat(updater): pure resolve logic merging live check with cached pre-download"
```

---

### Task 4: Make `AppUpdate` serializable + shared `UpdateJson`

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt`
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt`

- [ ] **Step 1: Annotate `AppUpdate`**

In `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt`, add the import and annotation. The existing declaration is:

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

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt app/src/full/java/com/nuvio/tv/updater/UpdateJson.kt
git commit -m "feat(updater): make AppUpdate serializable for cached pre-download"
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

After the existing `lastCheckAtMs` flow / `setLastCheckAtMs` (i.e. inside the class, before the closing brace), add:

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

### Task 7: `UpdateCheckWorker` — the daily worker

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt`

- [ ] **Step 1: Create the worker**

Create `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt`:

```kotlin
package com.nuvio.tv.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Once-a-day background check. If a newer, non-ignored build exists and the device is on an
 * unmetered network, downloads + SHA-256-verifies the APK and caches its path + metadata so
 * [UpdateViewModel] can offer an instant Install on next app open. No notifications.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Mirror the launch-time gate: never auto-act in debug builds.
        if (BuildConfig.IS_DEBUG_BUILD) return Result.success()

        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            UpdateWorkerEntryPoint::class.java,
        )
        val repo = entry.updateRepository()
        val downloader = entry.apkDownloader()
        val prefs = entry.updatePreferences()

        val update = repo.getLatestUpdate().getOrElse { return Result.retry() }
        prefs.setLastCheckAtMs(System.currentTimeMillis())

        val ignored = prefs.ignoredVersionCode.first()
        val cachedPath = prefs.predownloadApkPath.first()
        val cachedVersion = prefs.predownloadUpdateJson.first()
            ?.let { runCatching { UpdateJson.json.decodeFromString<com.nuvio.tv.updater.model.AppUpdate>(it) }.getOrNull()?.versionCode }
        val alreadyCached = cachedVersion == update.versionCode &&
            cachedPath != null && File(cachedPath).exists()

        val shouldDownload = UpdateDownloadDecision.shouldDownload(
            remoteVersionCode = update.versionCode,
            currentVersionCode = BuildConfig.VERSION_CODE,
            ignoredVersionCode = ignored,
            alreadyCachedForRemote = alreadyCached,
            isUnmetered = isUnmetered(applicationContext),
        )
        if (!shouldDownload) return Result.success()

        val dir = File(applicationContext.cacheDir, "updates")
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dest = File(dir, safeName)

        val file = downloader.download(update.assetUrl, dest) { _, _ -> }
            .getOrElse { return Result.retry() }

        val verified = runCatching { Checksum.verify(file, update.sha256) }.getOrDefault(false)
        if (!verified) {
            runCatching { file.delete() }
            return Result.retry()
        }

        prefs.setPredownload(file.absolutePath, UpdateJson.json.encodeToString(update))
        pruneExcept(dir, file)
        return Result.success()
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Keep only the freshly cached APK; delete any other stale APKs in the updates dir. */
    private fun pruneExcept(dir: File, keep: File) {
        dir.listFiles()?.forEach { f ->
            if (f.absolutePath != keep.absolutePath) runCatching { f.delete() }
        }
    }

    companion object {
        const val UNIQUE_NAME = "kevbox-daily-update-check"
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileFullDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt
git commit -m "feat(updater): daily CoroutineWorker that pre-downloads + verifies APK"
```

---

### Task 8: `UpdateWorkScheduler` — enqueue the periodic work

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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Schedules the once-a-day [UpdateCheckWorker]. Idempotent — safe to call on every app start
 * (KEEP preserves the already-scheduled work). The CONNECTED constraint gates only the cheap
 * version check; the worker itself enforces unmetered-only for the actual APK download.
 */
object UpdateWorkScheduler {

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
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
git commit -m "feat(updater): schedule the daily update-check worker"
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
        // Idempotent (KEEP), and best-effort so a WorkManager hiccup never blocks startup.
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

            // Drop a now-installed/stale cached pre-download so it can't resurface.
            if (!decision.isUpdateAvailable && cachedApkPath != null) {
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

- [ ] **Step 4: Sanity-check the dialog's Install path**

Read `app/src/full/java/com/nuvio/tv/updater/ui/UpdatePromptDialog.kt` and confirm that a non-null `state.downloadedApkPath` renders the **Install** action (the same state `downloadUpdate()` produces on success). If the dialog keys off a different field, adjust the state mapping in Step 2 to match. No code change expected if it keys off `downloadedApkPath`.

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
Expected: PASS, including `UpdateDownloadDecisionTest` (6) and `UpdateResolutionTest` (8).

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew assembleFullDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build the playstore flavor (must be untouched / still compiles)**

Run: `./gradlew assemblePlaystoreDebug`
Expected: BUILD SUCCESSFUL — confirms WorkManager + the worker are not referenced by the playstore flavor.

- [ ] **Step 4: R8 sanity on the full release**

Run: `./gradlew assembleFullRelease`
Expected: BUILD SUCCESSFUL with no R8 errors (`AppUpdate` is `@Keep`; the worker is referenced by name through WorkManager, which the default rules handle).

- [ ] **Step 5: Emulator smoke test (manual)**

Install the full debug APK, launch the app, and confirm it starts without crashing (the scheduler runs in `PluginRuntimeHooks.onApplicationCreate`). To exercise the worker on demand without waiting a day:

Run: `adb shell cmd jobscheduler run -f com.nuvio.tv <jobId>` *(or)* use WorkManager's test inspection; alternatively temporarily lower the period during local testing. Confirm via logcat that the worker runs and, when a newer `version.json` is served, caches an APK under `cacheDir/updates`.

- [ ] **Step 6: Final commit (if any verification fixups were needed)**

```bash
git add -A
git commit -m "test(updater): verify daily update check across full + playstore flavors"
```

---

## Self-Review Notes (author)

- **Spec coverage:** WorkManager dep (T1), download gate (T2), resolve logic (T3), serialization (T4), prefs (T5), EntryPoint (T6), worker (T7), scheduler (T8), full-only seam (T9), ViewModel surfacing + non-destructive failure + stale-clear (T10), both-flavor build + R8 (T11). All spec sections mapped.
- **Type consistency:** `UpdateDownloadDecision.shouldDownload(...)`, `UpdateResolution.resolve(...)` / `Decision(update, isUpdateAvailable, showDialog, installableApkPath)`, `UpdatePreferences.setPredownload/clearPredownload/predownloadApkPath/predownloadUpdateJson`, `UpdateWorkerEntryPoint.{updateRepository,apkDownloader,updatePreferences}`, `UpdateCheckWorker.UNIQUE_NAME`, `UpdateWorkScheduler.ensureScheduled` — names are used identically across tasks.
- **Catalog accessor:** TOML alias `androidx-work-runtime` → Gradle accessor `libs.androidx.work.runtime`.
- **Known manual gate:** T10 Step 4 verifies the dialog's Install trigger field before relying on `downloadedApkPath`.
