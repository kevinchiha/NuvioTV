package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * KevBox: catches ExoPlayer "playing" with its clock frozen because the TV refused to start a
 * passthrough AudioTrack.
 *
 * With AC-3/E-AC-3/DTS/TrueHD, ExoPlayer sends the encoded audio to the TV's own decoder and takes
 * its clock from that AudioTrack. When the TV's audio server has no free hardware decoder (seen on
 * a TCL on 2026-11-07: a dead process's offload tracks were never released), `AudioTrack.start()`
 * fails with status -1 but Media3 raises no error, so the player sits at 00:00 with the video
 * buffered and the existing 5001 PCM fallback never runs. This watchdog notices the frozen clock
 * and rebuilds the stream once with audio decoded in the app (forced PCM), which is what switching
 * to libmpv did by hand.
 */
internal object KevboxAudioClockStallPolicy {

    const val THRESHOLD_MS = 5_000L
    const val POLL_INTERVAL_MS = 1_000L

    /** Less than this ahead and a frozen clock may just be a starved buffer, not dead audio. */
    const val MIN_BUFFER_AHEAD_MS = 2_000L

    /** Movement under this is treated as no movement (position jitter at start). */
    const val ADVANCE_MS = 500L

    private val PASSTHROUGH_MIME_TYPES = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_TRUEHD,
    )

    data class Input(
        val isPlaying: Boolean,
        val armedAtPositionMs: Long,
        val positionMs: Long,
        val stuckForMs: Long,
        val bufferedPositionMs: Long,
        val audioMimeType: String?,
        val pcmAlreadyForced: Boolean,
    )

    enum class Decision { KEEP_WATCHING, STOP, FORCE_PCM }

    fun evaluate(input: Input): Decision = when {
        input.pcmAlreadyForced -> Decision.STOP
        input.audioMimeType !in PASSTHROUGH_MIME_TYPES -> Decision.STOP
        !input.isPlaying -> Decision.STOP
        input.positionMs - input.armedAtPositionMs >= ADVANCE_MS -> Decision.STOP
        input.stuckForMs < THRESHOLD_MS -> Decision.KEEP_WATCHING
        input.bufferedPositionMs - input.positionMs < MIN_BUFFER_AHEAD_MS -> Decision.KEEP_WATCHING
        else -> Decision.FORCE_PCM
    }
}

/** Armed from onIsPlayingChanged(true); ends itself once the clock moves or playback stops. */
internal fun PlayerRuntimeController.kevboxArmAudioClockStallWatchdog() {
    if (kevboxAudioClockStallJob?.isActive == true) return
    val player = _exoPlayer ?: return
    kevboxAudioClockStallJob = scope.launch {
        val armedAtPositionMs = player.currentPosition.coerceAtLeast(0L)
        val armedAtMs = System.currentTimeMillis()
        while (isActive) {
            delay(KevboxAudioClockStallPolicy.POLL_INTERVAL_MS)
            if (_exoPlayer !== player) return@launch
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val decision = KevboxAudioClockStallPolicy.evaluate(
                KevboxAudioClockStallPolicy.Input(
                    isPlaying = player.isPlaying,
                    armedAtPositionMs = armedAtPositionMs,
                    positionMs = positionMs,
                    stuckForMs = System.currentTimeMillis() - armedAtMs,
                    bufferedPositionMs = player.bufferedPosition,
                    audioMimeType = player.audioFormat?.sampleMimeType,
                    pcmAlreadyForced = hasTriedAudioPcmFallback,
                )
            )
            when (decision) {
                KevboxAudioClockStallPolicy.Decision.KEEP_WATCHING -> Unit
                KevboxAudioClockStallPolicy.Decision.STOP -> return@launch
                KevboxAudioClockStallPolicy.Decision.FORCE_PCM -> {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "AUDIO_CLOCK_STALL: playing but position stuck at ${positionMs}ms " +
                            "(buffered=${player.bufferedPosition}ms, audio=${player.audioFormat?.sampleMimeType}); " +
                            "rebuilding with audio decoded in the app"
                    )
                    hasTriedAudioPcmFallback = true
                    pendingAudioPcmFallbackRebuild = true
                    kevboxAudioClockStallJob = null
                    retryCurrentStreamWithSafeAudioFallback(positionMs)
                    return@launch
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.kevboxCancelAudioClockStallWatchdog() {
    kevboxAudioClockStallJob?.cancel()
    kevboxAudioClockStallJob = null
}
