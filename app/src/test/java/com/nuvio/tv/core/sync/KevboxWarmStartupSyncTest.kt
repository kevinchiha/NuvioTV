package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.StartupSyncState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KevBox: the warm (delta-only) startup sync decision reads the full-pull record saved on disk,
 * so a cold app start after the TV killed the process does not re-download every watched item
 * and progress entry. See KevboxWarmStartupSync.kt.
 */
class KevboxWarmStartupSyncTest {

    private val ttl = 6 * 60 * 60 * 1000L
    private val now = 10 * ttl

    private fun state(
        userId: String? = "user",
        lastFullPullAtMs: Long = now - 60_000L,
        includedProfileSettings: Boolean = true
    ) = StartupSyncState(
        lastFullPullUserId = userId,
        lastFullPullAtMs = lastFullPullAtMs,
        lastFullPullIncludedProfileSettings = includedProfileSettings
    )

    private fun decide(
        state: StartupSyncState = state(),
        force: Boolean = false,
        userId: String = "user",
        includeProfileSettings: Boolean = true,
        nowMs: Long = now,
        ttlMs: Long = ttl
    ) = canUseKevboxWarmStartupSync(
        force = force,
        userId = userId,
        includeProfileSettings = includeProfileSettings,
        state = state,
        nowMs = nowMs,
        ttlMs = ttlMs
    )

    @Test
    fun `a recent full pull on disk allows a warm sync after the process restarted`() {
        assertTrue(decide())
    }

    @Test
    fun `a forced sync is always a full pull`() {
        assertFalse(decide(force = true))
    }

    @Test
    fun `a full pull by another account does not count`() {
        assertFalse(decide(state = state(userId = "someone-else")))
        assertFalse(decide(state = state(userId = null)))
    }

    @Test
    fun `no full pull yet means a full pull`() {
        assertFalse(decide(state = state(lastFullPullAtMs = 0L)))
    }

    @Test
    fun `a full pull older than the ttl means a full pull`() {
        assertFalse(decide(state = state(lastFullPullAtMs = now - ttl)))
        assertTrue(decide(state = state(lastFullPullAtMs = now - ttl + 1)))
    }

    @Test
    fun `kevbox keeps a full pull good for a day so the first evening open stays quick`() {
        val hour = 60 * 60 * 1000L
        val dayTtl = KEVBOX_FULL_STARTUP_PULL_TTL_MS

        assertTrue(decide(state = state(lastFullPullAtMs = now - 23 * hour), ttlMs = dayTtl))
        assertFalse(decide(state = state(lastFullPullAtMs = now - 24 * hour), ttlMs = dayTtl))
    }

    @Test
    fun `a full pull stamped in the future means a full pull`() {
        // The TV clock moved backwards (stale RTC after a power cut); don't trust the record.
        assertFalse(decide(state = state(lastFullPullAtMs = now + 1)))
    }

    @Test
    fun `profile settings requested but missing from the last full pull means a full pull`() {
        assertFalse(decide(state = state(includedProfileSettings = false)))
        assertTrue(decide(state = state(includedProfileSettings = false), includeProfileSettings = false))
    }
}
