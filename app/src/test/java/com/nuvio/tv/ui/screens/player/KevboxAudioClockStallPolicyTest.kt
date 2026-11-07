package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.screens.player.KevboxAudioClockStallPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class KevboxAudioClockStallPolicyTest {

    private fun input(
        isPlaying: Boolean = true,
        armedAtPositionMs: Long = 0L,
        positionMs: Long = 0L,
        stuckForMs: Long = KevboxAudioClockStallPolicy.THRESHOLD_MS,
        bufferedPositionMs: Long = 53_000L,
        audioMimeType: String? = MimeTypes.AUDIO_E_AC3,
        pcmAlreadyForced: Boolean = false,
    ) = KevboxAudioClockStallPolicy.Input(
        isPlaying = isPlaying,
        armedAtPositionMs = armedAtPositionMs,
        positionMs = positionMs,
        stuckForMs = stuckForMs,
        bufferedPositionMs = bufferedPositionMs,
        audioMimeType = audioMimeType,
        pcmAlreadyForced = pcmAlreadyForced,
    )

    @Test
    fun `playing with buffered data but the clock frozen on encoded audio forces PCM`() {
        // The 2026-11-07 TCL case: E-AC-3 passthrough, AudioTrack.start failed, pos=0s, 53 s buffered.
        assertEquals(Decision.FORCE_PCM, KevboxAudioClockStallPolicy.evaluate(input()))
    }

    @Test
    fun `waits until the threshold has passed`() {
        assertEquals(
            Decision.KEEP_WATCHING,
            KevboxAudioClockStallPolicy.evaluate(input(stuckForMs = KevboxAudioClockStallPolicy.THRESHOLD_MS - 1))
        )
    }

    @Test
    fun `a moving clock ends the watch`() {
        assertEquals(
            Decision.STOP,
            KevboxAudioClockStallPolicy.evaluate(input(armedAtPositionMs = 60_000L, positionMs = 60_600L))
        )
    }

    @Test
    fun `stops when the player is no longer playing`() {
        assertEquals(Decision.STOP, KevboxAudioClockStallPolicy.evaluate(input(isPlaying = false)))
    }

    @Test
    fun `never fires twice once PCM is already forced`() {
        assertEquals(Decision.STOP, KevboxAudioClockStallPolicy.evaluate(input(pcmAlreadyForced = true)))
    }

    @Test
    fun `ignores audio that is decoded in the app anyway`() {
        assertEquals(Decision.STOP, KevboxAudioClockStallPolicy.evaluate(input(audioMimeType = MimeTypes.AUDIO_AAC)))
        assertEquals(Decision.STOP, KevboxAudioClockStallPolicy.evaluate(input(audioMimeType = null)))
    }

    @Test
    fun `covers every passthrough format`() {
        listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_EXPRESS,
            MimeTypes.AUDIO_TRUEHD,
        ).forEach { mime ->
            assertEquals(mime, Decision.FORCE_PCM, KevboxAudioClockStallPolicy.evaluate(input(audioMimeType = mime)))
        }
    }

    @Test
    fun `keeps watching while too little is buffered to blame the audio`() {
        assertEquals(
            Decision.KEEP_WATCHING,
            KevboxAudioClockStallPolicy.evaluate(input(positionMs = 10_000L, armedAtPositionMs = 10_000L, bufferedPositionMs = 11_000L))
        )
    }
}
