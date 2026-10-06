package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.StartupSyncState

/*
 * KevBox FORK DIVERGENCE (KevBox-only file; upstream has no copy, so merges never conflict here).
 *
 * Upstream StartupSyncService only takes the warm (delta-only) startup path when its in-memory
 * `lastPulledKey`/`lastPulledAtMs` match. Those reset whenever Android kills the process, which a
 * low-memory TV does all the time. Worse, upstream's warm path is unreachable even in a live
 * process: scheduleStartupPull returns early for a non-forced call with the same key, and every
 * caller asks for profile settings. So every startup re-downloaded and rewrote the member's whole
 * watched-items and watch-progress history (measured 2026-10-06 on a TCL TV: 3,321 + 1,048 rows,
 * ~13 s of background CPU, visible lag). This decision reads only the full-pull record that
 * StartupSyncPreferences already saves on disk, so the 6 h full-pull TTL works across restarts.
 * Do not drop this just because upstream "fixes process death"; check the warm path is reachable.
 *
 * Wired in at two marked spots in StartupSyncService.kt (pullRemoteData and pullWarmRemoteData).
 * On an upstream merge: keep both markers. If upstream rewrites canUseWarmSync or stops calling
 * markFullPull from the warm path, re-read this note and drop whichever half upstream now covers.
 */
internal fun canUseKevboxWarmStartupSync(
    force: Boolean,
    userId: String,
    includeProfileSettings: Boolean,
    state: StartupSyncState,
    nowMs: Long,
    ttlMs: Long
): Boolean {
    if (force) return false
    if (state.lastFullPullUserId != userId) return false
    if (state.lastFullPullAtMs <= 0L) return false
    val age = nowMs - state.lastFullPullAtMs
    if (age < 0L || age >= ttlMs) return false
    return !includeProfileSettings || state.lastFullPullIncludedProfileSettings
}
