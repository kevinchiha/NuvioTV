// KevBox upstream-sync note: KevBox-only test (upstream NuvioTV has no device-guard feature). Purely
// exercises the Android-free policy in [DeviceGuardDataStore.chooseDeviceId]; needs no emulator /
// Robolectric. Safe to drop wholesale if the device-limit feature is ever removed.
package com.nuvio.tv.data.local

import com.nuvio.tv.data.local.DeviceGuardDataStore.Companion.chooseDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure device-id policy behind [DeviceGuardDataStore.getOrCreateDeviceId].
 * `null` return = "no usable stable id, caller mints a random UUID".
 */
class DeviceGuardDataStoreTest {

    @Test
    fun `keeps an already-stored id and ignores android id (stability)`() {
        assertEquals("legacy-uuid-123", chooseDeviceId("legacy-uuid-123", "1234567890abcdef"))
    }

    @Test
    fun `uses a usable android id when nothing is stored`() {
        assertEquals("1234567890abcdef", chooseDeviceId(null, "1234567890abcdef"))
    }

    @Test
    fun `trims surrounding whitespace on the android id`() {
        assertEquals("1234567890abcdef", chooseDeviceId(null, "  1234567890abcdef\n"))
    }

    @Test
    fun `blank stored falls through to the android id`() {
        assertEquals("aid-xyz", chooseDeviceId("", "aid-xyz"))
    }

    @Test
    fun `null android id with nothing stored means mint a uuid`() {
        assertNull(chooseDeviceId(null, null))
    }

    @Test
    fun `blank android id means mint a uuid`() {
        assertNull(chooseDeviceId(null, "   "))
    }

    @Test
    fun `rejects the pre-froyo shared-bug android id (case-insensitive)`() {
        assertNull(chooseDeviceId(null, "9774d56d682e549c"))
        assertNull(chooseDeviceId(null, "9774D56D682E549C"))
    }

    @Test
    fun `rejects an all-zeros android id`() {
        assertNull(chooseDeviceId(null, "0000000000000000"))
    }
}
