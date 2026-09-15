package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The telemetry `record_error` call used to send only `PlaybackException.message`, which for
 * `ERROR_CODE_IO_BAD_HTTP_STATUS` (2004) is always "Source error". The HTTP status that the
 * stream host answered with lives in the cause chain and was dropped, so every 2004 row in
 * `member_event` looked identical (2026-09-15: a member whose TV was blocked by Torrentio
 * could not be told apart from a dead link). [toTelemetryMessage] keeps the status.
 */
class PlaybackErrorTelemetryMessageTest {

    private fun badHttpStatus(code: Int, statusText: String?): PlaybackException {
        val http = HttpDataSource.InvalidResponseCodeException(
            code,
            statusText,
            null,
            emptyMap(),
            DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build(),
            ByteArray(0)
        )
        return PlaybackException(
            "Source error",
            http,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
    }

    @Test
    fun `bad http status keeps the status code and text`() {
        assertEquals(
            "HTTP 403 Forbidden: Source error",
            badHttpStatus(403, "Forbidden").toTelemetryMessage()
        )
    }

    @Test
    fun `bad http status without status text keeps the code`() {
        assertEquals("HTTP 429: Source error", badHttpStatus(429, "").toTelemetryMessage())
    }

    @Test
    fun `non-http error sends the plain message`() {
        val error = PlaybackException(
            "Decoder init failed",
            IllegalStateException("boom"),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertEquals("Decoder init failed", error.toTelemetryMessage())
    }

    @Test
    fun `missing message falls back to a fixed string`() {
        val error = PlaybackException(null, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertEquals("playback error", error.toTelemetryMessage())
    }
}
