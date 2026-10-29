package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.AppUpdate

/**
 * Whether the app-open prompt stays hidden because a silent install is still expected to handle
 * the version (AC-15). The manual check in About never asks this.
 */
object SilentPromptHold {
    const val SAFETY_NET_MS = 259_200_000L   // 72 h after `after`

    /** Pure (AC-15): true while the silent path still owns [update], so the app-open prompt stays hidden. */
    fun holdsPrompt(
        sdkInt: Int,
        canRequestInstalls: Boolean,
        update: AppUpdate,
        silentState: SilentInstallState?,
        nowMs: Long,
    ): Boolean {
        if (sdkInt < SilentInstallDecision.MIN_SDK) return false
        if (!canRequestInstalls) return false
        val block = update.silentBlock() ?: return false

        // State saved for another versionCode counts as no attempts and not refused (AC-8).
        val state = silentState?.takeIf { it.versionCode == update.versionCode }
        if (state?.refused == true) return false
        if ((state?.attempts ?: 0) >= SilentInstallDecision.MAX_ATTEMPTS) return false

        // Overflow-safe form of `nowMs < afterMs + SAFETY_NET_MS`.
        return nowMs - block.afterMs < SAFETY_NET_MS
    }
}
