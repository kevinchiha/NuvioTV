package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate

/** Log tag for every silent-install line. Never log a user id, an email or a token under it. */
const val SILENT_LOG_TAG = "KevboxSilentUpdate"

/** Why a silent install may or may not run. Logged as is, so each failed check has its own name. */
enum class SilentReason {
    ELIGIBLE,
    // AC-2, listed in AC-2 order. eligibility() checks them in this order too, except
    // PREDOWNLOAD_CHECKSUM_MISMATCH: hashing the APK is slow, so it runs last.
    UPDATER_OFF, SDK_TOO_OLD, NO_INSTALL_PERMISSION, MANIFEST_FETCH_FAILED, NOT_AN_UPGRADE,
    NO_PREDOWNLOAD, PREDOWNLOAD_CHECKSUM_MISMATCH, VERSION_IGNORED, NO_SILENT_BLOCK, NOT_YET,
    SILENT_REFUSED, TOO_MANY_ATTEMPTS,
    // AC-3
    APP_ON_SCREEN, APP_RECENTLY_ON_SCREEN, EXTERNAL_PLAYBACK,
    // AC-10
    ANOTHER_SILENT_ATTEMPT, MEMBER_INSTALL_RUNNING, INSTALL_SCREEN_WAITING, MEMBER_INSTALL_RECENT,
    // AC-8: this process already committed a silent session for the version; Android is installing it
    ALREADY_COMMITTED,
}

/** Silent attempts made for one versionCode, and whether Android asked the member to confirm it. */
data class SilentInstallState(val versionCode: Int, val attempts: Int, val refused: Boolean)

/** What happened to a silent attempt, for [nextSilentState]. */
enum class SilentStateEvent { ATTEMPT, REFUSED }

/** Everything [SilentInstallDecision.eligibility] needs, read by the caller (AC-2). */
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

/**
 * Everything [SilentInstallDecision.quiet] needs (AC-3, AC-10). Times are
 * `SystemClock.elapsedRealtime()` values.
 */
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

/**
 * Pure checks that say whether a silent install may run now. No Android calls here: the caller
 * reads the clock, the SDK level and the app state and passes them in.
 */
object SilentInstallDecision {
    const val MIN_SDK = 31
    const val OUT_OF_SIGHT_MS = 120_000L
    const val MEMBER_INSTALL_QUIET_MS = 600_000L
    const val MAX_ATTEMPTS = 3

    /**
     * The first failing AC-2 check, or [SilentReason.ELIGIBLE]. The checksum is hashed last and
     * at most once, because it reads the whole APK.
     */
    fun eligibility(input: SilentEligibilityInput): SilentReason {
        if (!input.updaterEnabled) return SilentReason.UPDATER_OFF
        if (input.sdkInt < MIN_SDK) return SilentReason.SDK_TOO_OLD
        if (!input.canRequestInstalls) return SilentReason.NO_INSTALL_PERMISSION

        val live = input.liveUpdate ?: return SilentReason.MANIFEST_FETCH_FAILED
        if (live.versionCode <= input.currentVersionCode) return SilentReason.NOT_AN_UPGRADE

        if (!UpdateDownloadDecision.isCachedFor(input.cachedVersionCode, live.versionCode, input.cachedFileExists)) {
            return SilentReason.NO_PREDOWNLOAD
        }
        if (input.ignoredVersionCode == live.versionCode) return SilentReason.VERSION_IGNORED

        val block = live.silentBlock() ?: return SilentReason.NO_SILENT_BLOCK
        if (input.nowMs < block.afterMs && !isEarlyUser(input.signedInUserId, block.earlyUsers)) {
            return SilentReason.NOT_YET
        }

        // State saved for another versionCode does not count (AC-8).
        val state = input.silentState?.takeIf { it.versionCode == live.versionCode }
        if (state?.refused == true) return SilentReason.SILENT_REFUSED
        if ((state?.attempts ?: 0) >= MAX_ATTEMPTS) return SilentReason.TOO_MANY_ATTEMPTS

        if (!input.cachedSha256Matches()) return SilentReason.PREDOWNLOAD_CHECKSUM_MISMATCH
        return SilentReason.ELIGIBLE
    }

    /** True when [userId] matches an entry of [earlyUsers], both trimmed, ignoring case. */
    fun isEarlyUser(userId: String?, earlyUsers: List<String>): Boolean {
        val id = userId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return earlyUsers.any { it.trim().equals(id, ignoreCase = true) }
    }

    /**
     * The first reason the app is not out of sight and free (AC-3, AC-10), or
     * [SilentReason.ELIGIBLE]. A stored time above now means the box rebooted since, so it is
     * dropped; with no time left the app counts as out of sight since boot.
     */
    fun quiet(input: QuietInput): SilentReason {
        val now = input.nowElapsedMs
        if (input.startedActivities > 0) return SilentReason.APP_ON_SCREEN

        val lastSeen = listOfNotNull(input.lastStartElapsedMs, input.lastStopElapsedMs)
            .filter { it <= now }
            .maxOrNull() ?: 0L
        if (now - lastSeen < OUT_OF_SIGHT_MS) return SilentReason.APP_RECENTLY_ON_SCREEN

        if (input.externalPlaybackRunning) return SilentReason.EXTERNAL_PLAYBACK
        if (input.memberInstallRunning) return SilentReason.MEMBER_INSTALL_RUNNING
        if (input.installScreenWaiting) return SilentReason.INSTALL_SCREEN_WAITING

        val memberCommit = input.lastMemberCommitElapsedMs
        if (memberCommit != null && memberCommit <= now && now - memberCommit < MEMBER_INSTALL_QUIET_MS) {
            return SilentReason.MEMBER_INSTALL_RECENT
        }
        return SilentReason.ELIGIBLE
    }
}

/**
 * The stored state after [event] for [versionCode]. State saved for another versionCode starts
 * over, so each version gets its own attempts and its own "refused" flag.
 */
fun nextSilentState(current: SilentInstallState?, versionCode: Int, event: SilentStateEvent): SilentInstallState {
    val sameVersion = current?.takeIf { it.versionCode == versionCode }
    return when (event) {
        SilentStateEvent.ATTEMPT ->
            sameVersion?.copy(attempts = sameVersion.attempts + 1) ?: SilentInstallState(versionCode, 1, false)
        SilentStateEvent.REFUSED ->
            sameVersion?.copy(refused = true) ?: SilentInstallState(versionCode, 0, true)
    }
}
