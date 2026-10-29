package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class SilentClockTest {

    @Test
    fun `the server time wins over the box clock`() {
        assertEquals(1_000L, SilentClock.nowFor(serverTimeMs = 1_000L, boxNowMs = 999_000L))
        assertEquals(999_000L, SilentClock.nowFor(serverTimeMs = 999_000L, boxNowMs = 1_000L))
    }

    @Test
    fun `no server time falls back to the box clock`() {
        assertEquals(999_000L, SilentClock.nowFor(serverTimeMs = null, boxNowMs = 999_000L))
    }
}
