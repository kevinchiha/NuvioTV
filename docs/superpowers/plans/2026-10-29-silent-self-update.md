# Silent self-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `subagent-driven-development`. This plan
> states contracts, behaviour and constraints — **it deliberately contains no implementation or
> test bodies**. Read the file you are modifying before writing anything; the plan tells you what
> must be true, the codebase tells you how to say it.

**Goal:** A KevBox TV box on Android 12+ installs a newer KevBox TV release by itself, with no screen, once the app has been out of sight for 2 minutes and the live `version.json` allows it; every refusal or failure falls back quietly to today's "Update available" prompt.
**Architecture:** Two pure decision functions (eligibility from the manifest, the cache and stored state; "quiet" from app-visibility and member-install signals) feed one orchestrator, `SilentInstall.attempt`, which runs under a process-wide lock and drives a `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED`. Two triggers call it: a one-time WorkManager job queued 2 minutes after the app leaves the screen, and the end of the daily `UpdateCheckWorker` run. `release.sh` publishes the `silent` block; a debug-only Gradle switch points a debug build at a server on the host for the emulator run.
**Tech stack:** Kotlin, Android `PackageInstaller`, WorkManager 2.10, Hilt entry points, DataStore Preferences, kotlinx.serialization `JsonElement`, Bash + Python 3 for the release and test scripts.
**Spec:** `docs/superpowers/specs/2026-10-29-silent-self-update-design.md` (acceptance criteria cited as `AC-n`)

## Global constraints

- Branch `kevbox` only. Plain commit messages, no AI attribution, no `Co-Authored-By` line.
- Build and test the `full` flavor only; never build `playstore`.
- Run Gradle with `set -o pipefail` and read the output for `BUILD FAILED`; a piped `tail` alone hides failures.
- Full gate (the reviewer runs it, each command separately): `./gradlew :app:compileFullDebugKotlin`, then `./gradlew :app:testFullDebugUnitTest --continue`, then `./gradlew :app:assembleFullRelease --dry-run`. One test already fails on the base and does not count: `TraktProgressServiceRefreshTest` "change seen by a cancelled refresh is fetched by the next poll".
- Unit tests run with `isReturnDefaultValues = true`: `android.util.Log`, `Intent`, `SystemClock` and `org.json` are stubs that return 0/null/false. JUnit4, mockk and kotlinx-coroutines-test are available. No Robolectric. Pass clocks and Android facts into the code under test instead of reading them inside it.
- Updater unit tests live in `app/src/testFull/java/com/nuvio/tv/updater/` (not `app/src/test`).
- No test reads a source file as text to check what it contains (an import, a prop, a string); test behaviour through the code's own outputs.
- These existing tests must pass unchanged, without edits to their files: `InstallStatusActionTest`, `PendingInstallScreenTest`, `UpdateResolutionTest`, `UpdateDownloadDecisionTest`, `ProfileFetcherTest`, `InstallProfileTest` (AC-9).
- KevBox-only logic goes in `app/src/full`. Edits under `app/src/main` stay as small as possible: in this phase only the one gate expression in `MainActivity.kt` and in `AboutScreen.kt` (Task 1). Each such edit carries a `// KevBox:` comment.
- New updater classes go in `app/src/full/java/com/nuvio/tv/updater/`, package `com.nuvio.tv.updater`.
- Every silent-install log line uses the tag `SILENT_LOG_TAG` (`"KevboxSilentUpdate"`, declared in Task 3). No updater log line may contain a user id, an email, or a token. Log `early=true|false`, never the id.
- No new library dependencies.
- `scripts/*` is gitignored except the files already tracked and `scripts/tests/*.py`; new files directly under `scripts/` need `git add -f`. `docs/` is gitignored too (`git add -f`).
- No line added to `app/build.gradle.kts` may start with `versionCode =` or `versionName =`: `release.sh` and `scripts/release-metadata.sh` read and rewrite the first such line.
- Gated, never run: `./release.sh` (edit it freely, never execute it); `git push`; anything that reads `SUPABASE_DB_URL` from `local.properties`; `ssh persovps` writes under `/var/www/kevbox-tv`; any `adb install` or `adb push` to a device that is not the local emulator. Name the emulator by serial (`adb -s emulator-5554`, after checking `adb -s emulator-5554 emu avd name` prints `kevbox_tv`); never `adb -e` or a bare `adb`, because a real TV box (192.168.68.71:5555) is attached over network adb and `-e` can select it. Never print values from `local.properties` or `~/secrets/kevbox-keys/release.env`, and never edit that `release.env`.
- The AC-14 emulator runs are the orchestrator's job after the build. No task runs them.

---

### Task 1: Debug build switch for a host update server, and the trust check

Adds the `-PupdaterTestBaseUrl` / `-PupdaterTestVersionCode` properties so a debug build runs the updater against `http://10.0.2.2:<port>`, and replaces the two `!IS_DEBUG_BUILD` gates with one `BuildConfig.UPDATER_ENABLED` flag. The non-obvious part: the versionCode override must reach `BuildConfig.VERSION_CODE`, which the updater compares against, and not only the APK manifest.

**Delivers:** AC-12, AC-2 (check 1)

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/full/java/com/nuvio/tv/updater/UpdaterBuild.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateDownloadDecision.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt` (gate + two trust calls)
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt` (two trust calls)
- Modify: `app/src/main/java/com/nuvio/tv/MainActivity.kt` (one gate, ~line 1344)
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/settings/AboutScreen.kt` (one gate + its comment, ~line 170)
- Test: `app/src/testFull/java/com/nuvio/tv/updater/UpdaterTestUrlTrustTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: BuildConfig fields `UPDATER_ENABLED: boolean` and `UPDATER_TEST_BASE_URL: String`; Gradle properties `updaterTestBaseUrl`, `updaterTestVersionCode`.

```kotlin
// UpdaterBuild.kt
object UpdaterBuild {
    /** AC-2 check 1: a release build, or a debug build made with -PupdaterTestBaseUrl. */
    val enabled: Boolean
    /** isTrustedAssetUrl with this build's UPDATE_BASE_URL and UPDATER_TEST_BASE_URL. */
    fun isTrusted(url: String): Boolean
}

// UpdateDownloadDecision (signature change; the default keeps every 2-argument call compiling)
fun isTrustedAssetUrl(assetUrl: String, baseUrl: String, testBaseUrl: String = ""): Boolean
```

**Behaviour:**
1. Gradle reads `providers.gradleProperty("updaterTestBaseUrl")` and `providers.gradleProperty("updaterTestVersionCode")`. A blank or missing property counts as unset. The build fails at configuration with a clear message when the URL does not parse to an `http`/`https` URI with a host, or the version code is not a positive integer.
2. Debug build type: `UPDATER_TEST_BASE_URL` is the test URL with trailing `/` removed, or `""`. `UPDATER_ENABLED` is `true` only when the test URL is set. `UPDATE_BASE_URL` is the test URL when set; otherwise it keeps today's value (dev properties, then local properties, then `https://tv.kevbox.dev`).
3. Release build type: `UPDATER_TEST_BASE_URL = ""` and `UPDATER_ENABLED = true`, hardcoded, whatever properties are passed. Benchmark build type overrides `UPDATER_ENABLED = false` (it inherits release through `initWith` and has `IS_DEBUG_BUILD = true`, so the updater stays off there as today).
4. With `updaterTestVersionCode` set, every output of each **debug** variant gets that versionCode, set inside the existing `androidComponents { onVariants(selector().withBuildType("debug")) { ... } }` block. Release and benchmark variants keep `defaultConfig.versionCode`.
5. `isTrustedAssetUrl(asset, base, test)`: today's rule (https, host equals the base host) still accepts. In addition, when `test` is non-blank, an `http` asset whose host equals the test host (case-insensitive) **and** whose effective port equals the test URL's effective port (`-1` means 80 for http, 443 for https) is accepted. Nothing else is accepted.
6. `UpdaterBuild.enabled` returns `BuildConfig.UPDATER_ENABLED`. `UpdaterBuild.isTrusted(url)` calls `isTrustedAssetUrl(url, BuildConfig.UPDATE_BASE_URL, BuildConfig.UPDATER_TEST_BASE_URL)`.
7. `UpdateCheckWorker.doWork` returns `Result.success()` early when `!UpdaterBuild.enabled` (replaces `if (BuildConfig.IS_DEBUG_BUILD)`). Every trust call in the worker and in `UpdateViewModel` goes through `UpdaterBuild.isTrusted`.
8. The two main-source gates become `AppFeaturePolicy.inAppUpdatesEnabled && BuildConfig.UPDATER_ENABLED`.

**Constraints:**
- `BuildConfig.VERSION_CODE` must follow the override. AGP 8.13 should take it from the variant's main output. Check the generated `BuildConfig.java` (Verify below). If it still shows the `defaultConfig` code, stop and report; do not change `defaultConfig`, because release would then pick up the test code.
- `UPDATER_ENABLED` is a build-type field, so both flavors get it. `MainActivity`/`AboutScreen` compile for both, and playstore needs the field to exist.
- Cleartext http needs no manifest change: `network_security_config.xml` already sets `cleartextTrafficPermitted="true"`.
- Keep `IS_DEBUG_BUILD` as it is. Other code reads it (Sentry environment, member-access preview, settings).
- The new unit tests pass explicit `baseUrl`/`testBaseUrl` arguments. They never read `BuildConfig`, since the test build's values depend on which properties were passed.

**Test intents:**
- `with no test URL an http asset on the base host is refused`
- `with the test URL an http asset on its exact host and port is trusted`
- `with the test URL the same host on another port is refused`
- `with the test URL another http host is refused`
- `with the test URL an https asset on the base host is still trusted`

**Verify:**
```bash
set -o pipefail
./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.UpdaterTestUrlTrustTest' --tests 'com.nuvio.tv.updater.UpdateDownloadDecisionTest' 2>&1 | tail -20
./gradlew :app:generateFullDebugBuildConfig :app:generateFullReleaseBuildConfig -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2002 -Pdebuggable=true 2>&1 | tail -5
grep -E 'VERSION_CODE|IS_DEBUG_BUILD|UPDATER_|UPDATE_BASE_URL' app/build/generated/source/buildConfig/full/debug/com/nuvio/tv/BuildConfig.java app/build/generated/source/buildConfig/full/release/com/nuvio/tv/BuildConfig.java
./gradlew :app:generateFullDebugBuildConfig 2>&1 | tail -3
grep -E 'VERSION_CODE|UPDATER_' app/build/generated/source/buildConfig/full/debug/com/nuvio/tv/BuildConfig.java
```
Expected: tests pass. With the properties, debug shows `VERSION_CODE = 2002`, `UPDATER_ENABLED = true`, `UPDATE_BASE_URL = "http://10.0.2.2:8765"`, and release shows `VERSION_CODE = 1061` (the `defaultConfig` value), `IS_DEBUG_BUILD = false`, `UPDATER_TEST_BASE_URL = ""`, `UPDATER_ENABLED = true`. Without the properties, debug shows `VERSION_CODE = 1061`, `UPDATER_ENABLED = false`, `UPDATER_TEST_BASE_URL = ""`.

**Commit:** `feat(updater): debug build switch to test the updater against a host server`

---

### Task 2: The `silent` block in version.json

Maps an optional `"silent": {"after": <epoch ms>, "earlyUsers": [...]}` onto `AppUpdate`. The field is typed as a raw `JsonElement` because a malformed block must never break the rest of the manifest. A typed `@Serializable` class would throw on `"silent": "yes"`, and the prompt would stop working.

**Delivers:** AC-1

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt` (`UpdateManifest` + `AppUpdate`)
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentBlock.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateRepository.kt` (`parseManifest`)
- Test: `app/src/testFull/java/com/nuvio/tv/updater/UpdateManifestMappingTest.kt` (add tests; AC-1 names this file)

**Interfaces:**
- Consumes: nothing.
- Produces:

```kotlin
// UpdateManifest: new last field
@SerialName("silent") val silent: JsonElement? = null

// AppUpdate: new last fields (defaults keep cached JSON from older builds decodable)
val silentAfterMs: Long? = null,
val silentEarlyUsers: List<String> = emptyList(),

// SilentBlock.kt
data class SilentBlock(val afterMs: Long, val earlyUsers: List<String>) {
    companion object {
        /** Null for anything but a valid block (AC-1). Never throws. */
        fun parse(element: JsonElement?): SilentBlock?
    }
}
/** The block this update carries, or null. */
fun AppUpdate.silentBlock(): SilentBlock?
```

**Behaviour:**
1. `SilentBlock.parse` returns a block only when the element is a `JsonObject` whose `after` is a JSON number primitive (not a quoted string) that converts to a `Long` greater than 0.
2. `earlyUsers` missing or JSON `null` gives an empty list. Present, it must be a `JsonArray` of string primitives; anything else (`"x"`, a number, an array holding a non-string) makes the whole block null. Entries are kept as written; matching trims them later (Task 3).
3. `parseManifest` sets `silentAfterMs = block?.afterMs` and `silentEarlyUsers = block?.earlyUsers ?: emptyList()`. Every other field maps exactly as today.
4. `AppUpdate.silentBlock()` returns `SilentBlock(silentAfterMs, silentEarlyUsers)` when `silentAfterMs` is positive, else null.

**Constraints:**
- `parse` must not throw on any input: `"after": "soon"`, `"after": 1.5`, nested objects. Catch conversion failures inside it so `parseManifest` keeps going.
- `UpdateManifest` and `AppUpdate` stay `@Keep @Serializable`. `JsonElement` has a built-in serializer, so R8 needs no new rule.
- Keep the existing `Json { ignoreUnknownKeys = true; isLenient = true }` config. The lenient flag is why `after` must be checked as a non-string primitive and not read through `longOrNull` alone.

**Test intents:**
- `manifest without a silent key maps as today with no silent block`
- `a valid block maps after and earlyUsers, and a missing earlyUsers gives an empty list`
- `each malformed block gives no silent block and the rest still maps`: one test per case `"silent": "yes"`, `"silent": {}`, `"after": "soon"`, `"after": 0`, `"after": -5`, `"earlyUsers": "x"`. Assert `versionCode` and `assetUrl` too, so a swallowed exception cannot pass.
- `AppUpdate JSON cached by an older build decodes with no silent block`: decode the old field set through `UpdateJson.json`.

**Verify:** `set -o pipefail; ./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.UpdateManifestMappingTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all mapping tests pass.

**Commit:** `feat(updater): read the silent install block from version.json`

---

### Task 3: Silent-install decisions and per-version state

The two pure functions that say whether a silent install may run, plus the stored per-version state (attempt count, "refused" flag). Everything here is pure Kotlin except `UpdatePreferences`, so the AC-2 one-test-per-reason rule is cheap to meet.

**Delivers:** AC-2, AC-3 (decision), AC-8 (state), AC-10 (decision)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentInstallDecision.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdatePreferences.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/SilentInstallDecisionTest.kt`

**Interfaces:**
- Consumes: `AppUpdate.silentBlock()` (Task 2).
- Produces:

```kotlin
const val SILENT_LOG_TAG = "KevboxSilentUpdate"

enum class SilentReason {
    ELIGIBLE,
    // AC-2, in check order
    UPDATER_OFF, SDK_TOO_OLD, NO_INSTALL_PERMISSION, MANIFEST_FETCH_FAILED, NOT_AN_UPGRADE,
    NO_PREDOWNLOAD, PREDOWNLOAD_CHECKSUM_MISMATCH, VERSION_IGNORED, NO_SILENT_BLOCK, NOT_YET,
    SILENT_REFUSED, TOO_MANY_ATTEMPTS,
    // AC-3
    APP_ON_SCREEN, APP_RECENTLY_ON_SCREEN, EXTERNAL_PLAYBACK,
    // AC-10
    ANOTHER_SILENT_ATTEMPT, MEMBER_INSTALL_RUNNING, INSTALL_SCREEN_WAITING, MEMBER_INSTALL_RECENT,
}

data class SilentInstallState(val versionCode: Int, val attempts: Int, val refused: Boolean)

data class SilentEligibilityInput(
    val updaterEnabled: Boolean,
    val sdkInt: Int,
    val canRequestInstalls: Boolean,
    val liveUpdate: AppUpdate?,            // null: this run's version.json fetch failed
    val currentVersionCode: Int,
    val cachedVersionCode: Int?,
    val cachedFileExists: Boolean,
    val cachedSha256Matches: () -> Boolean, // called last, only once every other check passes
    val ignoredVersionCode: Int?,
    val nowMs: Long,
    val signedInUserId: String?,           // null: not read, or not a FullAccount
    val silentState: SilentInstallState?,
)

data class QuietInput(
    val startedActivities: Int,
    val lastStartElapsedMs: Long?,
    val lastStopElapsedMs: Long?,
    val nowElapsedMs: Long,
    val externalPlaybackRunning: Boolean,
    val memberInstallRunning: Boolean,
    val installScreenWaiting: Boolean,
    val lastMemberCommitElapsedMs: Long?,
)

object SilentInstallDecision {
    const val MIN_SDK = 31
    const val OUT_OF_SIGHT_MS = 120_000L
    const val MEMBER_INSTALL_QUIET_MS = 600_000L
    const val MAX_ATTEMPTS = 3
    fun eligibility(input: SilentEligibilityInput): SilentReason
    fun isEarlyUser(userId: String?, earlyUsers: List<String>): Boolean
    fun quiet(input: QuietInput): SilentReason
}

// UpdatePreferences additions
val silentInstallState: Flow<SilentInstallState?>
suspend fun recordSilentAttempt(versionCode: Int)
suspend fun markSilentRefused(versionCode: Int)
```

**Behaviour:**
1. `eligibility` returns the first failing reason in AC-2 order. (1) `UPDATER_OFF`. (2) `SDK_TOO_OLD` when `sdkInt < 31`. (3) `NO_INSTALL_PERMISSION`. (4) `MANIFEST_FETCH_FAILED` when `liveUpdate == null`, `NOT_AN_UPGRADE` when its versionCode is not above `currentVersionCode`. (5) `NO_PREDOWNLOAD` when `cachedVersionCode != live.versionCode` or the file is missing. (6) `VERSION_IGNORED` when `ignoredVersionCode == live.versionCode`. (7) `NO_SILENT_BLOCK` when `live.silentBlock()` is null. (8) `NOT_YET` when `nowMs < afterMs` and `isEarlyUser` is false. (9) `SILENT_REFUSED`, then `TOO_MANY_ATTEMPTS` when `attempts >= 3`. Last, the rest of AC-2 check 5: `PREDOWNLOAD_CHECKSUM_MISMATCH` when `cachedSha256Matches()` is false. Otherwise `ELIGIBLE`.
2. Check 9 reads `silentState` only when `silentState.versionCode == live.versionCode`. State stored for any other versionCode counts as 0 attempts, not refused (AC-8).
3. `isEarlyUser`: false for a null or blank id; otherwise true when any entry, trimmed, equals the trimmed id ignoring case.
4. `quiet` returns, in this order: `APP_ON_SCREEN` when `startedActivities > 0`; `APP_RECENTLY_ON_SCREEN` when `nowElapsedMs - ref < 120_000`, where `ref` is the larger of the two stored times after dropping any value that is null or greater than `nowElapsedMs` (that means a reboot), and `ref = 0` when both are dropped ("out of sight since boot"); `EXTERNAL_PLAYBACK`; `MEMBER_INSTALL_RUNNING`; `INSTALL_SCREEN_WAITING`; `MEMBER_INSTALL_RECENT` when a member commit time exists, is not above now, and `now - it < 600_000`. Otherwise `ELIGIBLE`.
5. `UpdatePreferences` stores one state under three keys: `silent_version_code` (int), `silent_attempts` (int), `silent_refused` (boolean). `silentInstallState` emits null when no versionCode is stored.
6. `recordSilentAttempt(v)`: when the stored versionCode differs from `v`, reset to `(v, 1, false)`; otherwise increment attempts. `markSilentRefused(v)`: when the stored versionCode differs, reset to `(v, 0, true)`; otherwise set `refused = true`. Each change is one `dataStore.edit`, and both compute the new state through one pure function in `SilentInstallDecision.kt`: `fun nextSilentState(current: SilentInstallState?, versionCode: Int, event: SilentStateEvent): SilentInstallState` with `enum class SilentStateEvent { ATTEMPT, REFUSED }`, so the transition is unit-tested without the cached DataStore.

**Constraints:**
- `eligibility` calls `cachedSha256Matches` at most once, as its very last check, after every cheap check has passed. The APK is ~100 MB, and hashing it on every leave-the-app event when an earlier check already fails is wasted work on a TV.
- No Android calls in `SilentInstallDecision.kt`: no `Build`, no `SystemClock`, no `Log`. Callers pass the values in.
- Do not touch the existing keys or the corruption handler in `UpdatePreferences` (KEVBOX-TV-5).

**Test intents:**
- `eligibility returns <REASON> when only that check fails`: one test per AC-2 reason (12), each starting from one all-pass fixture and breaking one field, plus `eligibility is ELIGIBLE when every check passes` and `an early user passes before after`. AC-2 requires one test per reason; this intent covers that family.
- `per-version silent state resets on a new versionCode`: on read, a state `(v-1, 3, true)` still gives `ELIGIBLE`; through `nextSilentState`, ATTEMPT on `(v-1, 3, true)` gives `(v, 1, false)`, REFUSED on it gives `(v, 0, true)`, ATTEMPT on `(v, 1, false)` gives `(v, 2, false)`, REFUSED on `(v, 2, false)` gives `(v, 2, true)`, and both work from `null`.
- `the checksum is not computed when an earlier check fails`: a lambda that counts its calls.
- `quiet treats a stored time above now as out of sight since boot`: last stop 5 000 000, now 200 000 gives `ELIGIBLE`; now 60 000 with no stored times gives `APP_RECENTLY_ON_SCREEN`.
- `quiet returns each AC-3 and AC-10 reason it owns`: one assertion per reason at its boundary (119 999 vs 120 000 ms; 599 999 vs 600 000 ms). `ANOTHER_SILENT_ATTEMPT` is not one of them; Task 7 sets it from the lock.

**Verify:** `set -o pipefail; ./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.SilentInstallDecisionTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, every test passes.

**Commit:** `feat(updater): decide when a silent install may run`

---

### Task 4: "In use" signals and the member-install guard

Collects the live facts that `quiet` needs: activity start/stop counts and times recorded app-wide, whether the external-player keep-alive service runs, and what the member's own Install flow is doing. Registration goes through the full-flavor `PluginRuntimeHooks`, so no upstream file changes.

**Delivers:** AC-3 (signals), AC-10 (guard), AC-9 (member path keeps its behaviour)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/AppVisibilityTracker.kt`
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentInstallGuard.kt`
- Modify: `app/src/full/java/com/nuvio/tv/core/runtime/PluginRuntimeHooks.kt` (register the tracker)
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt` (`installUpdateOrRequestPermission`)
- Modify: `app/src/full/java/com/nuvio/tv/updater/ApkInstaller.kt` (member `installWithSession`: one line after commit)
- Test: `app/src/testFull/java/com/nuvio/tv/updater/SilentInstallGuardTest.kt`

**Interfaces:**
- Consumes: `QuietInput` (Task 3), `PendingInstallScreen.pending`.
- Produces:

```kotlin
object AppVisibilityTracker {
    fun register(application: Application)
    val startedActivities: Int
    fun lastStartElapsedMs(context: Context): Long?
    fun lastStopElapsedMs(context: Context): Long?
}

object SilentInstallGuard {
    val attemptLock: Mutex                 // AC-10: one silent attempt per process at a time
    fun memberInstallStarted()
    fun memberInstallFinished()
    val memberInstallRunning: Boolean
    fun memberSessionCommitted(nowElapsedMs: Long)
    val lastMemberCommitElapsedMs: Long?
    fun isExternalPlaybackRunning(context: Context): Boolean
    fun quietInput(context: Context): QuietInput
}
```

**Behaviour:**
1. `register` installs one `Application.ActivityLifecycleCallbacks`. `onActivityStarted` increments an in-memory counter and stores `SystemClock.elapsedRealtime()` as the last start. `onActivityStopped` decrements it (never below 0) and stores the last stop.
2. The two times live in `SharedPreferences` file `kevbox_app_visibility`, keys `last_start_elapsed_ms` and `last_stop_elapsed_ms`, written with `apply()`. They survive process death; the counter does not, and a cold process starts at 0, which is correct because no activity can be started without the process.
3. `PluginRuntimeHooks.onApplicationCreate` calls `AppVisibilityTracker.register(application)` inside its own `try/catch`, next to the existing `UpdateWorkScheduler` call.
4. `isExternalPlaybackRunning` returns true when `ActivityManager.getRunningServices(Int.MAX_VALUE)` lists `com.nuvio.tv.core.player.ExternalPlaybackKeepAliveService` for this package. Any exception returns false.
5. `memberInstallStarted/Finished` keep a counter; `memberInstallRunning` is `counter > 0`.
6. `UpdateViewModel.installUpdateOrRequestPermission`: the launched install job calls `memberInstallStarted()` first and `memberInstallFinished()` in a `finally`, wrapping both the profile fetch and `ApkInstaller.launchInstall`.
7. `ApkInstaller.installWithSession` (the member path) calls `SilentInstallGuard.memberSessionCommitted(SystemClock.elapsedRealtime())` right after `session.commit` succeeds.
8. `quietInput(context)` builds a `QuietInput` from the tracker, `SystemClock.elapsedRealtime()`, `isExternalPlaybackRunning`, the member flags, and `PendingInstallScreen.pending.value != null`.

**Constraints:**
- `getRunningServices` is deprecated, but it still returns the caller's own services. It is used so that `ExternalPlaybackKeepAliveService` (upstream, `app/src/main`) needs no edit.
- Keep the counting and storage logic testable without Android: callbacks delegate to internal functions that take a clock value and a small store interface. Unit tests cannot use `SharedPreferences` (the stub returns null).
- The member path's behaviour does not change (AC-9): same session, same `base.dm` rule, same fallbacks. Only the two guard calls are added.
- Deliberate omission: the tracker does not cancel a queued silent job when an activity starts. The job re-checks `quiet` and stops on `APP_ON_SCREEN`.
- Minor gap install-6: also store the member commit time in `kevbox_app_visibility` (key `last_member_commit_elapsed_ms`), and have `lastMemberCommitElapsedMs` fall back to it, so a fresh process still waits 10 minutes after a member commit; a stored value above now means a reboot and is dropped.

**Test intents:**
- `member install running while started without finish, and not after finish`
- `quietInput carries the last member commit time`: drive through the internal builder with fake signals, expect `MEMBER_INSTALL_RECENT` from `quiet`.
- `the counter never goes below zero and a stop records the stop time`: through the internal store and clock seam.

**Verify:** `set -o pipefail; ./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.SilentInstallGuardTest' --tests 'com.nuvio.tv.updater.InstallStatusActionTest' --tests 'com.nuvio.tv.updater.PendingInstallScreenTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

**Commit:** `feat(updater): track when the app is out of sight and guard member installs`

---

### Task 5: The silent PackageInstaller session

Adds the silent session to `ApkInstaller` and the permission that lets Android skip the confirm screen. Unlike the member path, a missing compile profile never routes to the plain installer, because the plain installer always shows a screen.

**Delivers:** AC-6, AC-9, AC-13 (committed line)

**Files:**
- Modify: `app/src/full/java/com/nuvio/tv/updater/ApkInstaller.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/InstallResultReceiver.kt` (companion constants only; Task 6 handles them)
- Modify: `app/src/full/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SILENT_LOG_TAG` (Task 3).
- Produces:

```kotlin
// ApkInstaller
/** Creates a silent session and writes base.apk (+ base.dm). Abandons it and rethrows on failure. Blocking I/O. */
fun openSilentSession(context: Context, apkFile: File, profileFile: File?): Int
/** Commits a session opened by openSilentSession. Throws if Android refuses the commit. */
fun commitSilentSession(context: Context, sessionId: Int, versionCode: Int, profileName: String?)
fun abandonSession(context: Context, sessionId: Int)   // never throws

// InstallResultReceiver.Companion
const val EXTRA_SILENT = "tv.kevbox.updater.SILENT"
const val EXTRA_VERSION_CODE = "tv.kevbox.updater.VERSION_CODE"
```

Manifest line, added inside `<manifest>` before `<application>`:

```xml
    <!-- Lets the updater install a KevBox TV update without the confirm screen (Android 12+). -->
    <uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />
```

**Behaviour:**
1. `openSilentSession` first abandons this app's earlier sessions, as `installWithSession` does. It then creates `SessionParams(MODE_FULL_INSTALL)` with `setAppPackageName(context.packageName)`, `setSize(apk + profile length)` and `setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)`. It writes `base.apk`, and writes `base.dm` only when `profileFile != null`, through the existing `writeFile` helper. It returns the session id.
2. `commitSilentSession` reopens the session by id and commits it with a broadcast `PendingIntent` to `InstallResultReceiver` that carries `EXTRA_SILENT = true` and `EXTRA_VERSION_CODE = versionCode`. Use request code `sessionId` and the same mutable flags as the member path. It then logs under `SILENT_LOG_TAG`, verbatim: `Committed silent session <id> (versionCode <n>, profile=<file name|none>)`.
3. `abandonSession` wraps `packageInstaller.abandonSession` in `runCatching`.
4. `launchInstall`, `installWithSession` and `plainInstall` keep their current behaviour (apart from Task 4's one guard line).

**Constraints:**
- `setRequireUserAction` exists from API 31. Annotate the silent functions `@RequiresApi(31)`; Task 7 calls them only after `eligibility` has passed check 2.
- Never call `PendingInstallScreen.post` or `startActivity` from the silent functions.
- Abandoning earlier sessions is safe here because AC-10 (Task 3/4) blocks a silent attempt while a member session may wait for its confirm screen, and the receiver abandons a refused silent session itself.
- No unit test: these are `PackageInstaller` calls that only an install proves. AC-14 covers them on the emulator.

**Test intents:** none. Android framework calls only; the AC-14 emulator run checks them.

**Verify:** `set -o pipefail; ./gradlew :app:compileFullDebugKotlin 2>&1 | tail -10 && ./gradlew :app:processFullDebugMainManifest 2>&1 | tail -3`, then confirm the permission appears in the merged manifest under `app/build/intermediates/` (find the file with the glob tool).
Expected: BUILD SUCCESSFUL, the merged manifest holds `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

**Commit:** `feat(updater): silent package installer session`

---

### Task 6: Silent results in InstallResultReceiver

Handles the result of a silent session without ever showing a screen. A refusal marks the version so no more silent attempts run for it, and the prompt takes over on the next app open.

**Delivers:** AC-7, AC-8 (result side), AC-13 (result line)

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentResultAction.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/InstallResultReceiver.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/SilentResultActionTest.kt`

**Interfaces:**
- Consumes: `EXTRA_SILENT`, `EXTRA_VERSION_CODE`, `ApkInstaller.abandonSession` (Task 5); `UpdatePreferences.markSilentRefused` (Task 3); `SILENT_LOG_TAG`.
- Produces:

```kotlin
enum class SilentResultAction {
    INSTALLED, REFUSED, FAILED;
    companion object { fun forStatus(status: Int): SilentResultAction }
}
```

**Behaviour:**
1. `forStatus`: `STATUS_SUCCESS` gives `INSTALLED`, `STATUS_PENDING_USER_ACTION` gives `REFUSED`, every other status (including `STATUS_FAILURE_ABORTED`) gives `FAILED`.
2. `onReceive` checks `EXTRA_SILENT` first. When it is true, only the silent branch runs; the existing `InstallStatusAction` branch is never reached.
3. Silent branch, every outcome: log under `SILENT_LOG_TAG`, verbatim: `Silent result for versionCode <n>: status <status> (<ACTION>)`, with ` message=<EXTRA_STATUS_MESSAGE>` appended when present.
4. `REFUSED`: abandon the session from `PackageInstaller.EXTRA_SESSION_ID`, then `markSilentRefused(versionCode)`. Do not read `Intent.EXTRA_INTENT`. Post nothing to `PendingInstallScreen`, start no activity.
5. `FAILED` and `INSTALLED`: log only. No fallback to the plain installer, no `PendingInstallScreen` post. The attempt was already counted before commit (Task 7).
6. The DataStore write runs through `goAsync()` on a background coroutine, with `finish()` in a `finally`. The receiver gets `UpdatePreferences` through `EntryPointAccessors.fromApplication(context, UpdateWorkerEntryPoint::class.java)`.
7. A silent intent with no `EXTRA_VERSION_CODE` (value ≤ 0) logs and writes nothing.

**Constraints:**
- The non-silent branch must behave exactly as today (AC-9); `InstallStatusActionTest` and `PendingInstallScreenTest` stay untouched.
- The member prompt needs no change for AC-7. `UpdateResolution` already offers Install when the cached pre-download matches, and a refusal deletes nothing.

**Test intents:**
- `pending user action maps to REFUSED`
- `success maps to INSTALLED`
- `aborted and failure statuses map to FAILED`: `STATUS_FAILURE_ABORTED`, `STATUS_FAILURE`, `STATUS_FAILURE_INCOMPATIBLE`. This mapping differs from `InstallStatusAction`, which treats ABORTED as NOTHING.

**Verify:** `set -o pipefail; ./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.SilentResultActionTest' --tests 'com.nuvio.tv.updater.InstallStatusActionTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

**Commit:** `feat(updater): handle silent install results without a screen`

---

### Task 7: One silent attempt, end to end

`SilentInstall.attempt` runs the whole sequence under the process-wide lock: evaluate, maybe read the account, check quiet, write the session, check quiet again, count the attempt, commit. The order of the last four steps is what AC-3 and AC-8 test.

**Delivers:** AC-2, AC-3, AC-8, AC-10, AC-13

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentInstall.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateWorkerEntryPoint.kt` (add `authManager()`)
- Test: `app/src/testFull/java/com/nuvio/tv/updater/SilentInstallTest.kt`

**Interfaces:**
- Consumes: `SilentInstallDecision`, `SilentReason`, `SilentEligibilityInput`, `SilentInstallState` (Task 3); `SilentInstallGuard` (Task 4); `ApkInstaller.openSilentSession/commitSilentSession/abandonSession` (Task 5); `UpdaterBuild` (Task 1); `ProfileFetcher.fetch`; `Checksum.verify`; `UpdatePreferences`.
- Produces:

```kotlin
enum class SilentTrigger(val label: String) { JOB("job"), DAILY_CHECK("daily-check") }

object SilentInstall {
    /** One silent attempt for [live] (this run's version.json result). Never throws. */
    suspend fun attempt(context: Context, live: Result<AppUpdate>, trigger: SilentTrigger)
}

// UpdateWorkerEntryPoint addition
fun authManager(): com.nuvio.tv.core.auth.AuthManager
```

**Behaviour:**
1. `attempt` calls `SilentInstallGuard.attemptLock.tryLock()`. When the lock is held, it logs the evaluation line with `ANOTHER_SILENT_ATTEMPT` and returns. It always unlocks in a `finally`.
2. It builds `SilentEligibilityInput` from `UpdaterBuild.enabled`, `Build.VERSION.SDK_INT`, `ApkInstaller.canRequestPackageInstalls`, `live.getOrNull()`, `BuildConfig.VERSION_CODE`, the cached pre-download (path + decoded `AppUpdate` versionCode from `UpdatePreferences`), a memoized `Checksum.verify(file, live.sha256)`, the ignored versionCode, `System.currentTimeMillis()`, `signedInUserId = null`, and `silentInstallState.first()`.
3. When the result is `NOT_YET` and the live block's `earlyUsers` is non-empty, it waits up to 15 000 ms for `authManager.authState` to leave `AuthState.Loading`, takes `FullAccount.userId` (anything else gives null), and evaluates again. `early` is `isEarlyUser(userId, earlyUsers)`; it is false when the account was not read.
4. It evaluates `SilentInstallDecision.quiet(SilentInstallGuard.quietInput(context))` BEFORE `eligibility`, and runs `eligibility` (with its checksum last) only when quiet is `ELIGIBLE`, so a box whose member is back on screen never hashes the APK. The logged reason is the quiet reason when quiet fails, else the eligibility reason.
5. It logs exactly one evaluation line per attempt, verbatim: `Silent check [<trigger.label>] versionCode=<live versionCode|none>: <REASON> early=<true|false>`. The reason is the first failure or `ELIGIBLE`. A non-eligible attempt returns here.
6. It fetches the profile with `ProfileFetcher.fetch(downloader, live, apkFile) { UpdaterBuild.isTrusted(it) }`.
7. It calls `openSilentSession`. If that throws: `recordSilentAttempt(versionCode)`, log `Silent session for versionCode <n> could not be written (attempt counted): <exception class simple name>`, return.
8. It evaluates `quiet` again. When that is not `ELIGIBLE`: `abandonSession`, log `Silent session <id> abandoned before commit: <REASON>`, return without counting an attempt.
9. It awaits `recordSilentAttempt(versionCode)`, then calls `commitSilentSession(..., profileName = profile?.name)`. If commit throws, it abandons the session and logs `Silent commit failed for session <id>: <exception class simple name>`; the attempt stays counted.

**Constraints:**
- Put every Android, clock, file and DataStore call behind one internal interface with an Android implementation, so the sequence runs in unit tests against a fake. `attempt(context, ...)` builds the Android implementation and delegates.
- The attempt is recorded before `commit`, and awaited, not fire-and-forget. The result broadcast may never arrive once Android kills the process for the install (spec Decisions).
- Never log the user id, the email, or a token. Exception messages can hold more than the class name, so log the class name only.
- `attempt` never throws: wrap the body in `runCatching` and log unexpected failures as `Silent attempt failed: <exception class simple name>`. Cancellation is the exception: rethrow `CancellationException` so WorkManager can stop the job.
- Run on `Dispatchers.IO`. Hashing and the session copy are blocking.
- Deliberate omission: no retry, no rescheduling. The next leave-the-app event or daily check tries again (AC-4).
- Minor gap flow-2: if the attempt is cancelled after `openSilentSession` and before `commit` (REPLACE re-queue, network constraint lost), abandon the open session in a `finally` under `withContext(NonCancellable)`, then rethrow.

**Test intents:**
- `a failed second quiet check abandons the session and counts no attempt`
- `a session write that throws counts one attempt and commits nothing`
- `the attempt is recorded before commit`: the fake records call order.
- `the account is read only on NOT_YET with earlyUsers, and an early user proceeds`: the fake counts account reads. Assert that no logged line contains the fixture user id or email.
- `a held lock logs ANOTHER_SILENT_ATTEMPT and touches no session`

**Verify:** `set -o pipefail; ./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.SilentInstallTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

**Commit:** `feat(updater): run one silent install attempt under a process-wide lock`

---

### Task 8: Triggers: the leave-the-app job and the daily check

Wires the two callers. Leaving the app queues a one-time job 2 minutes out. The daily `UpdateCheckWorker` tries a silent install at the end of a run that left a valid pre-download.

**Delivers:** AC-4, AC-5, AC-13 (queued line), AC-15

**Files:**
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentInstallWorker.kt`
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentInstallScheduler.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/AppVisibilityTracker.kt` (call the scheduler)
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateCheckWorker.kt`
- Create: `app/src/full/java/com/nuvio/tv/updater/SilentPromptHold.kt`
- Modify: `app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt` (`checkForUpdates`, the `force = false` path only)
- Test: `app/src/testFull/java/com/nuvio/tv/updater/SilentInstallSchedulerTest.kt`
- Test: `app/src/testFull/java/com/nuvio/tv/updater/SilentPromptHoldTest.kt`

**Interfaces:**
- Consumes: `SilentInstall.attempt`, `SilentTrigger` (Task 7); `AppVisibilityTracker` (Task 4); `AppUpdate.silentBlock()` (Task 2); `UpdaterBuild.enabled` (Task 1).
- Produces:

```kotlin
class SilentInstallWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val UNIQUE_NAME = "kevbox-silent-install"
        const val DELAY_MS = 120_000L
    }
}

object SilentInstallScheduler {
    /** Called when the last started activity stops. Returns at once; reads the cache off the main thread. */
    fun onAppLeftScreen(context: Context)
    /** Pure: whether leaving the screen should queue the job. */
    fun shouldQueue(cached: AppUpdate?, currentVersionCode: Int, updaterEnabled: Boolean, sdkInt: Int): Boolean
}

object SilentPromptHold {
    const val SAFETY_NET_MS = 259_200_000L   // 72 h after `after`
    /** Pure (AC-15): true while the silent path still owns [update], so the app-open prompt stays hidden. */
    fun holdsPrompt(
        sdkInt: Int,
        canRequestInstalls: Boolean,
        update: AppUpdate,
        silentState: SilentInstallState?,
        nowMs: Long,
    ): Boolean
}
```

**Behaviour:**
1. `AppVisibilityTracker.onActivityStopped`: when the counter reaches 0 and `activity.isChangingConfigurations` is false, it calls `SilentInstallScheduler.onAppLeftScreen(applicationContext)` **after** storing the stop time.
2. `onAppLeftScreen` launches on a process-wide `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. It reads and decodes `predownloadUpdateJson` through `UpdateWorkerEntryPoint`, and when `shouldQueue(...)` is true it enqueues `OneTimeWorkRequestBuilder<SilentInstallWorker>()` with `setInitialDelay(120 000 ms)` and no constraints, via `enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, ...)`. It then logs verbatim: `Silent job queued (versionCode <n>, delay 120 s)`.
3. `shouldQueue` is true only when `updaterEnabled`, `sdkInt >= 31`, `cached != null`, `cached.versionCode > currentVersionCode`, and `cached.silentBlock() != null`.
4. `SilentInstallWorker.doWork`: when `!UpdaterBuild.enabled`, return success. Otherwise fetch with `updateRepository().getLatestUpdate()`, call `SilentInstall.attempt(applicationContext, result, SilentTrigger.JOB)`, and return `Result.success()` on every path, a failed fetch included.
5. `UpdateCheckWorker.runCheck`: on the success path only (never before a `Result.retry()`), after the final `pruneStale`, when the run kept a valid pre-download for the fetched `update` (`keepName != null`, which covers both "downloaded now" and "already cached" for an upgrade), call `SilentInstall.attempt(applicationContext, kotlin.Result.success(update), SilentTrigger.DAILY_CHECK)` (inside the worker a bare `Result` is `ListenableWorker.Result`).
6. A failure inside `onAppLeftScreen` (WorkManager or DataStore) is caught and logged; it never reaches the lifecycle callback.
7. `holdsPrompt` is true only when all hold: `sdkInt >= 31`; `canRequestInstalls`; `update.silentBlock() != null`; the state for `update.versionCode` (state stored for another versionCode counts as 0 attempts, not refused) is not refused and has `attempts < 3`; and `nowMs < afterMs + SAFETY_NET_MS`. Otherwise false.
8. `UpdateViewModel.checkForUpdates`: when `force` is false and `decision.update` is not null, it reads `silentInstallState.first()` and, when `SilentPromptHold.holdsPrompt(Build.VERSION.SDK_INT, ApkInstaller.canRequestPackageInstalls(context), decision.update, state, System.currentTimeMillis())` is true, sets `showDialog = false` (everything else in the UI state stays as `UpdateResolution` decided, so About still shows the version and an instant Install). It logs one line under `SILENT_LOG_TAG`: `App-open prompt held for versionCode <n> (silent install owns it)`. The `force = true` path is untouched.

**Constraints:**
- `REPLACE`, not `KEEP`: leaving again restarts the 2-minute clock from the latest leave.
- The job has no network constraint (AC-4). Offline, the fetch fails and the attempt logs `MANIFEST_FETCH_FAILED`.
- The job never returns `retry()`. A retry would keep re-trying a box that is in use; the next leave event is the retry.
- Store the stop time before queuing. The job's `quiet` check measures 120 000 ms from that stop time, and WorkManager never runs a delayed job early.
- The daily worker runs the attempt inside its own `runMutex`. That is fine: the attempt has its own lock, and the daily worker does not wait on it.
- `UpdateResolution` and its test stay unchanged (AC-9); the hold is applied in `UpdateViewModel` after `resolve`.

**Test intents:**
- `queues for a cached upgrade with a silent block`
- `does not queue without a silent block, for a non-upgrade, below API 31, or with the updater off`: one assertion per case.
- `holdsPrompt is true while silent owns the version and false for each condition that ends it`: below API 31, no permission, no silent block, refused, 3 attempts, at `after + 72 h` (boundary: one ms before is true), plus state for another versionCode ignored.

**Verify:** `set -o pipefail; ./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.SilentInstallSchedulerTest' --tests 'com.nuvio.tv.updater.SilentPromptHoldTest' --tests 'com.nuvio.tv.updater.UpdateResolutionTest' --tests 'com.nuvio.tv.updater.UpdateDownloadDecisionTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

**Commit:** `feat(updater): try a silent install after leaving the app and after the daily check`

---

### Task 9: release.sh writes the silent block

A small Python helper renders the whole `version.json`, so each AC-11 case can run alone, with no ssh and no Gradle, and be checked with `python3 -m json.tool`. `release.sh` calls it to check `SILENT_EARLY_USERS` before it bumps anything, and again to render the manifest it uploads. This goes a step past the spec, which asks only for the silent block to be buildable alone. Rendering the whole file is what lets the `--no-silent` case pass `json.tool` (a missing block prints nothing, and empty output fails `json.tool`). It also replaces the hand-escaped heredoc with `json.dumps`.

**Delivers:** AC-11

**Files:**
- Create: `scripts/release_manifest.py` (needs `git add -f`)
- Create: `scripts/tests/test_release_manifest.py` (tracked by the existing `!scripts/tests/*.py` rule)
- Modify: `release.sh`

**Interfaces:**
- Consumes: nothing from the Kotlin tasks. The JSON shape must match Task 2's parser.
- Produces (Task 10 imports these):

```python
SILENT_DELAY_MS = 86_400_000
def parse_early_users(raw: str | None) -> list[str]   # raises ValueError("SILENT_EARLY_USERS entry <k> is not a UUID")
def silent_block(now_ms: int, early_users: list[str]) -> dict
def render_manifest(*, version_code: int, version_name: str, url: str, sha256: str, notes: str,
                    profiles: list[dict], silent: dict | None) -> str
```

CLI:
```
python3 scripts/release_manifest.py check
python3 scripts/release_manifest.py render --version-code N --version-name V --url U --sha256 S \
    --notes TEXT --profiles-json '[...]' [--no-silent] [--now-ms MS]
```
Both read `SILENT_EARLY_USERS` from the environment.

**Behaviour:**
1. `parse_early_users`: None, empty or whitespace-only gives `[]`. Otherwise split on `,`, trim each piece, and require every piece to match `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`. The first failure raises with its 1-based position; an empty piece (`a,,b` or a trailing comma) fails too. The message never includes the value.
2. `silent_block(now, users)` returns `{"after": now + 86400000, "earlyUsers": users}`.
3. `render_manifest` returns `json.dumps(..., indent=2, ensure_ascii=False) + "\n"` with keys in this order: `versionCode`, `versionName`, `url`, `sha256`, `notes`, `profiles`, then `silent` only when `silent` is not None. `notes` gets every control character (newline, tab, CR included) replaced by a space, as today's bash escaping does.
4. `check` exits 0 when the list is valid, else prints the error to stderr and exits 1. `render` without `--no-silent` validates the same way (exit 1, no stdout on failure) and uses `--now-ms` or the current epoch ms. With `--no-silent` it writes no `silent` key and skips validation.
5. `release.sh`: an optional first argument `--no-silent` sets `NO_SILENT=1` and is shifted off before `VER`. The `[[ -f "$KEYENV" ]] || err ...; source "$KEYENV"` pair moves up to right after `REPO`, `GRADLE` and `KEYENV` are assigned (the script runs under `set -u`, so it cannot sit before them), still before step 1. Then, unless `NO_SILENT=1`, `SILENT_EARLY_USERS="${SILENT_EARLY_USERS:-}" python3 "$REPO/scripts/release_manifest.py" check || err "Fix SILENT_EARLY_USERS in $KEYENV"` runs, all before step 1 (the bump).
6. Step 4 renders the manifest with `render` (passing `--no-silent` when set and `--profiles-json "[$PROFILES_JSON]"`). It replaces the remote heredoc with: `printf '%s' "$MANIFEST" | ssh "$SSH_ALIAS" -p "$SSH_PORT" "sudo tee $WEB_ROOT/version.json > /dev/null"`. Remove the bash `NOTES_ESC` lines and the comment about the quoted heredoc, which no longer apply.
7. The header comment gains, verbatim:
```
# Usage: ./release.sh [--no-silent] <versionName> [release notes...]
#   --no-silent  publish without a "silent" block: boxes show the prompt and never install by themselves.
# Silent install: by default version.json carries "silent": {"after": <publish time + 24 h>, "earlyUsers": [...]}.
#   Boxes signed in as an early user may install silently right away; every other box waits 24 h.
#   Early users come from this line in ~/secrets/kevbox-keys/release.env (Supabase auth user ids, never emails):
#     SILENT_EARLY_USERS=<uuid>,<uuid>
#   A blank or missing line means no early users. Any entry that is not a UUID stops the release before the bump.
```

**Constraints:**
- Never run `release.sh`, and never open, print or edit the real `release.env`.
- `SILENT_EARLY_USERS` must reach Python as an explicit env assignment: `source` sets a shell variable, not an exported one.
- Keep step order and everything else in the script: bump, build, upload, sha on the host, commit, push.

**Test intents:**
- `two valid ids give a silent block with both, after = now + 86400000`
- `blank or missing SILENT_EARLY_USERS gives earlyUsers []`
- `an email entry is rejected with its position and without its value`
- `--no-silent writes no silent key`
- `notes with newlines and tabs render as one line`

**Verify:**
```bash
PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests -v 2>&1 | tail -5
R="python3 scripts/release_manifest.py render --version-code 1062 --version-name 1.2.0-test --url https://tv.kevbox.dev/x.apk --sha256 abc --notes Test --profiles-json [] --now-ms 1000"
SILENT_EARLY_USERS="11111111-1111-1111-1111-111111111111, 2222222A-2222-2222-2222-222222222222" $R | python3 -m json.tool
SILENT_EARLY_USERS="" $R | python3 -m json.tool
SILENT_EARLY_USERS="kevin@example.com" python3 scripts/release_manifest.py check; echo "exit $?"
SILENT_EARLY_USERS="kevin@example.com" $R --no-silent | python3 -m json.tool
bash -n release.sh && echo "syntax ok"
```
Expected: unittest OK. The first output shows `"after": 86401000` with both ids, the second `"earlyUsers": []`, the third prints `SILENT_EARLY_USERS entry 1 is not a UUID` and `exit 1`, the fourth has no `silent` key, and the last prints `syntax ok`.

**Commit:** `feat(release): publish a silent install block in version.json`

---

### Task 10: Emulator test server and run steps for AC-14

A host-side script that serves `version.json` and an APK on `http://127.0.0.1:<port>`, which the emulator reaches as `http://10.0.2.2:<port>`. Its docstring holds the exact AC-14 steps for the orchestrator. It reuses Task 9's renderer so the test manifest has the shape `release.sh` publishes.

**Delivers:** AC-14 (support; the orchestrator does the run), AC-13 (logcat check steps)

**Files:**
- Create: `scripts/updater_test_server.py` (needs `git add -f`)
- Create: `scripts/tests/test_updater_test_server.py`

**Interfaces:**
- Consumes: `render_manifest` and `parse_early_users` (to validate each `--early-user`) from `scripts/release_manifest.py` (Task 9).
- Produces, CLI:
```
python3 scripts/updater_test_server.py --apk PATH --version-code N --after-hours H [--early-user UUID ...] [--port 8765]
```
and `build_manifest(apk_path, version_code, after_hours, early_users, port, now_ms) -> str` for the tests.

**Behaviour:**
1. It copies (or hard-links) the APK into a fresh temp dir as `kevbox-test-<N>.apk`, computes its SHA-256, and writes `version.json` with `versionName "test-<N>"`, `url "http://10.0.2.2:<port>/kevbox-test-<N>.apk"`, `notes "Emulator test build <N>"`, `profiles []` and `silent = {"after": now_ms + round(H * 3600000), "earlyUsers": [...]}`. Build `silent` directly, not with `silent_block`: that helper adds a fixed 24 h, while the test needs H hours.
2. It serves the temp dir with `http.server` bound to `127.0.0.1` only, prints the manifest and the URL, and runs until Ctrl-C.
3. The module docstring carries the run steps below, verbatim.

Docstring run steps (verbatim):
```
AC-14 emulator run. Local kevbox_tv emulator only; never a real TV box. A real TV is attached
over network adb, so every command names the serial: E=emulator-5554, and first check
  adb -s $E emu avd name   -> kevbox_tv   (stop if it is anything else)
Start the emulator first (run-emulator skill). PORT=8765, N=2001.

Build both APKs (both use the release key through the debug signing config):
  set -o pipefail
  ./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2001 -Pdebuggable=true 2>&1 | tail -5
  cp app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk /tmp/kevbox-2001.apk
  ./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2002 2>&1 | tail -5
  cp app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk /tmp/kevbox-2002.apk
  ./gradlew :app:assembleFullDebug -PupdaterTestBaseUrl=http://10.0.2.2:8765 -PupdaterTestVersionCode=2003 -Pdebuggable=true 2>&1 | tail -5
  cp app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk /tmp/kevbox-2003.apk
Never uninstall: the emulator's debug app holds Kevin's sign-in. Each run installs a higher
versionCode over what is there (same release key). The test APKs are debuggable
(-Pdebuggable=true) so that the restore step at the end can go back down to the normal build.

Run 1 (after passed):
  adb -s $E install -r /tmp/kevbox-2001.apk
  adb -s $E shell appops set tv.kevbox.debug REQUEST_INSTALL_PACKAGES allow
  setsid python3 scripts/updater_test_server.py --apk /tmp/kevbox-2002.apk --version-code 2002 --after-hours -1 >/tmp/kevbox-updater-server.log 2>&1 < /dev/null & echo $! > /tmp/kevbox-updater-server.pid
  adb -s $E logcat -c
  adb -s $E shell am start -n tv.kevbox.debug/com.nuvio.tv.MainActivity
  Wait for "Pre-downloaded + verified" in: adb -s $E logcat -d -s UpdateCheckWorker
  adb -s $E shell input keyevent KEYCODE_HOME
  Wait 5 minutes with no input, then:
  adb -s $E shell dumpsys package tv.kevbox.debug | grep versionCode        -> versionCode=2002
  adb -s $E logcat -d -s KevboxSilentUpdate InstallResultReceiver ApkInstaller UpdateCheckWorker
     -> "Silent job queued", "Silent check [job] versionCode=2002: ELIGIBLE",
        "Committed silent session", and "Silent result ... status 0 (INSTALLED)" if the
        result arrived before Android killed the process.
  No package-installer screen: adb -s $E shell dumpsys activity activities | grep -ci packageinstaller -> 0
  Stop the server: kill "$(cat /tmp/kevbox-updater-server.pid)"; wait until port 8765 is free
  (ss -ltn | grep -c :8765 -> 0) before run 2.

Run 2 (not yet, one early user that is not this box; 2002 is installed after run 1):
  adb -s $E shell appops set tv.kevbox.debug REQUEST_INSTALL_PACKAGES allow
  setsid python3 scripts/updater_test_server.py --apk /tmp/kevbox-2003.apk --version-code 2003 --after-hours 24 --early-user 00000000-0000-0000-0000-000000000000 >/tmp/kevbox-updater-server.log 2>&1 < /dev/null & echo $! > /tmp/kevbox-updater-server.pid
  adb -s $E logcat -c
  Open the app as in run 1, wait for the pre-download line, press HOME, wait 5 minutes.
  adb -s $E shell dumpsys package tv.kevbox.debug | grep versionCode        -> versionCode=2002
  adb -s $E logcat -d -s KevboxSilentUpdate  -> "Silent check [job] versionCode=2003: NOT_YET early=false"
  Open the app again: no update prompt appears (AC-15), and logcat -s KevboxSilentUpdate shows
  "App-open prompt held for versionCode 2003". Settings > About > check for updates shows the
  prompt with Install.
  Stop the server as in run 1.

Restore the normal debug build (keeps Kevin's sign-in; works because 2002 is debuggable):
  ./gradlew :app:assembleFullDebug 2>&1 | tail -5
  adb -s $E install -r -d app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
  adb -s $E shell dumpsys package tv.kevbox.debug | grep versionCode   -> the defaultConfig versionCode

AC-13 privacy check, when an account is signed in on the emulator:
  adb -s $E logcat -d -s KevboxSilentUpdate InstallResultReceiver ApkInstaller UpdateCheckWorker ProfileFetcher | grep -ci -e '<account user id>' -e '<account email>'   -> 0
  (The filter keeps to updater tags on purpose: AuthManager's own debug log prints the raw user id today, and that is outside this phase.)

If UpdateCheckWorker logs "No download this run" on an emulator network that counts as metered,
report it: the pre-download only runs on unmetered networks.
```

**Constraints:**
- Bind to `127.0.0.1`, never `0.0.0.0`: 10.0.2.2 inside the emulator is the host's loopback, and nothing else needs to reach this server.
- The script never calls adb, ssh or Gradle. It only serves files; the docstring steps are for a person or the orchestrator.
- No `profiles` entry (AC-14), so the silent session writes `base.apk` alone and that path gets exercised.

**Test intents:**
- `manifest points at 10.0.2.2 on the given port with the APK's sha256`
- `after is now plus the given hours, and early users pass through`: `-1` and `24` hours.

**Verify:** `PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests -v 2>&1 | tail -5 && python3 scripts/updater_test_server.py --help`
Expected: unittest OK; help lists `--apk`, `--version-code`, `--after-hours`, `--early-user`, `--port`.

**Commit:** `test(updater): host server and run steps for the emulator silent-update check`
