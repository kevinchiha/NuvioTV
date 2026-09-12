package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Video

internal fun Addon.supportsStreamResource(type: String, videoId: String): Boolean =
    resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { videoId.startsWith(it) }
            }
    }

internal data class PlaybackAvailability(
    val addons: List<Addon> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    val isLoaded: Boolean = false,
    private val cachedMeta: (String, String) -> Meta? = { _, _ -> null },
    // KevBox FORK DIVERGENCE: when true, canStream() never blocks a Play tap. Upstream's gate reads
    // the live addon list, and an addon whose manifest has not resolved yet carries no resources, so
    // on the first Play after a sign-in (cold manifest cache) it would toast "Playback isn't
    // available… with your current setup" and hide the member's own source — the exact symptom the
    // 2026-08-30 manifest-storm fix removed. A signed-out member would get the same toast instead of
    // the "sign in" wording StreamRepositoryImpl produces. KevBox therefore lets every Play reach the
    // stream screen, which already explains failures in member-facing terms. Only the provider
    // composable sets this; upstream's unit tests construct the class without it and keep their
    // semantics. Do not drop this field or the early return on merge.
    val allowUnverifiedPlayback: Boolean = false
) {
    fun canStream(
        type: String,
        videoId: String,
        contentId: String = videoId,
        video: Video? = null
    ): Boolean = allowUnverifiedPlayback ||
        video?.takeIf { it.id == videoId }?.streams?.isNotEmpty() == true ||
        cachedMeta(type, contentId)?.videos?.any { it.id == videoId && it.streams.isNotEmpty() } == true ||
        addons.any { it.enabled && it.supportsStreamResource(type, videoId) } ||
        scrapers.any { it.enabled && it.supportsType(type) }
}
