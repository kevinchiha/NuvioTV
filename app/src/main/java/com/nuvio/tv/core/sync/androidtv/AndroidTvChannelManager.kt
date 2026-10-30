package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val TAG = "TvChannelSync"

@Singleton
class AndroidTvChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences,
) {
    private val syncedProgramFingerprints = ConcurrentHashMap<String, ChannelProgramFingerprint>()
    private val syncedLibraryFingerprints = ConcurrentHashMap<String, LibraryProgramFingerprint>()

    private data class ChannelProgramFingerprint(
        val title: String?,
        val position: Int?,
        val duration: Int?,
        val imageUri: String?,
        val logoUri: String?,
        val sortOrder: Int,
        val season: Int?,
        val episode: Int?,
        val lastEngagementTime: Long
    )

    private data class LibraryProgramFingerprint(
        val title: String,
        val poster: String?,
        val posterShape: PosterShape,
        val background: String?,
        val logo: String?,
        val description: String?,
        val releaseInfo: String?,
        val addonBaseUrl: String?,
        val sortOrder: Int
    ) {
        constructor(entry: LibraryEntry, sortOrder: Int) : this(
            entry.name, entry.poster, entry.posterShape, entry.background, entry.logo,
            entry.description, entry.releaseInfo, entry.addonBaseUrl, sortOrder
        )
    }

    fun isSupported(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    } catch (_: Exception) {
        false
    }

    /** One launcher row: where its id is stored and how the provider tells it apart. */
    private class ChannelSpec(
        val internalProviderId: String,
        val displayNameRes: Int,
        val getStoredId: suspend () -> Long?,
        val setStoredId: suspend (Long) -> Unit,
        val clearStoredId: suspend () -> Unit,
    )

    // The Continue Watching channel keeps the bare package name it has always used, so
    // existing installs recover their channel. Every other channel gets its own suffix.
    private val continueWatchingSpec = ChannelSpec(
        internalProviderId = context.packageName,
        displayNameRes = R.string.tv_channel_continue_watching,
        getStoredId = prefs::getChannelId,
        setStoredId = prefs::setChannelId,
        clearStoredId = prefs::clearChannelId,
    )

    private val librarySpec = ChannelSpec(
        internalProviderId = "${context.packageName}.library",
        displayNameRes = R.string.tv_channel_library,
        getStoredId = prefs::getLibraryChannelId,
        setStoredId = prefs::setLibraryChannelId,
        clearStoredId = prefs::clearLibraryChannelId,
    )

    /**
     * Returns the Continue Watching channel id, creating or reusing the channel as needed.
     * Returns null on non-leanback devices or on failure.
     */
    suspend fun ensureChannel(): Long? = ensureChannel(continueWatchingSpec)

    /** Same as [ensureChannel] for the Library channel. */
    suspend fun ensureLibraryChannel(): Long? = ensureChannel(librarySpec)

    // Several callers (live observer, background job, app-to-background) can reach
    // ensureChannel at once; without the lock each may create its own copy of the channel.
    private val ensureChannelLock = Mutex()

    private suspend fun ensureChannel(spec: ChannelSpec): Long? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        ensureChannelLock.withLock { ensureChannelLocked(spec) }
    }

    private suspend fun ensureChannelLocked(spec: ChannelSpec): Long? {
        return runCatching {
            val stored = spec.getStoredId()
            // Without a channel list, trust the stored id and leave cleanup for a later run.
            val channels = queryOwnChannels()
                ?: return@runCatching stored ?: createChannel(spec)
            val claims = claimChannels(channels)
            val claimed = claims[spec]
            if (stored != null && stored != claimed) {
                Log.d(TAG, "Stored channel $stored gone; recreating")
                spec.clearStoredId()
            }

            val id = if (claimed != null) {
                if (claimed != stored) {
                    Log.d(TAG, "Reusing orphaned channel $claimed (${spec.internalProviderId})")
                    spec.setStoredId(claimed)
                    writeChannelLogo(claimed)
                }
                // Channels from older builds have no provider id; tag them so a lost stored
                // id can still find them.
                if (channels.first { it.id == claimed }.internalProviderId != spec.internalProviderId) {
                    tagChannel(claimed, spec.internalProviderId)
                }
                claimed
            } else {
                createChannel(spec) ?: return@runCatching null
            }

            deleteStrayChannels(channels, keep = claims.values.toSet() + id)
            id
        }.onFailure { Log.w(TAG, "ensureChannel failed", it) }.getOrNull()
    }

    /** A channel row as the TV provider lists it; the provider only lists our own. */
    private class ProviderChannel(
        val id: Long,
        val internalProviderId: String?,
        val packageName: String?,
        val type: String?,
    )

    /** Null when the provider gives no usable list. */
    private fun queryOwnChannels(): List<ProviderChannel>? {
        val projection = arrayOf(
            TvContractCompat.Channels._ID,
            TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.Channels.COLUMN_PACKAGE_NAME,
            TvContractCompat.Channels.COLUMN_TYPE
        )
        return context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndex(TvContractCompat.Channels._ID)
            val providerIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            val packageIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_PACKAGE_NAME)
            val typeIdx = c.getColumnIndex(TvContractCompat.Channels.COLUMN_TYPE)
            if (idIdx < 0 || providerIdx < 0) return@use null
            val result = mutableListOf<ProviderChannel>()
            while (c.moveToNext()) {
                result += ProviderChannel(
                    id = c.getLong(idIdx),
                    internalProviderId = c.getString(providerIdx),
                    packageName = if (packageIdx >= 0) c.getString(packageIdx) else null,
                    type = if (typeIdx >= 0) c.getString(typeIdx) else null,
                )
            }
            result
        }
    }

    /**
     * Picks the channel each row should use, without creating anything: the stored id while
     * it exists, else a channel with the row's provider id (exact match: a prefix match would
     * grab another row). Continue Watching also takes an untagged channel, which older builds
     * created. Every row is claimed, not just the one being ensured, so a cleanup run for one
     * row never deletes the other.
     */
    private suspend fun claimChannels(channels: List<ProviderChannel>): Map<ChannelSpec, Long> {
        val claims = mutableMapOf<ChannelSpec, Long>()
        val free = { channel: ProviderChannel -> channel.id !in claims.values }
        for (spec in listOf(continueWatchingSpec, librarySpec)) {
            val stored = spec.getStoredId()
            val pick = channels.firstOrNull { it.id == stored && free(it) }
                ?: channels.firstOrNull { it.internalProviderId == spec.internalProviderId && free(it) }
            if (pick != null) claims[spec] = pick.id
        }
        if (continueWatchingSpec !in claims) {
            channels.firstOrNull { it.internalProviderId.isNullOrBlank() && free(it) }
                ?.let { claims[continueWatchingSpec] = it.id }
        }
        return claims
    }

    private suspend fun createChannel(spec: ChannelSpec): Long? {
        val appLinkUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
        val channel = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(context.getString(spec.displayNameRes))
            .setAppLinkIntentUri(appLinkUri)
            .setInternalProviderId(spec.internalProviderId)
            .build()

        val inserted = context.contentResolver.insert(
            TvContractCompat.Channels.CONTENT_URI,
            channel.toContentValues()
        ) ?: return null

        val id = ContentUris.parseId(inserted)
        spec.setStoredId(id)
        writeChannelLogo(id)
        TvContractCompat.requestChannelBrowsable(context, id)
        Log.d(TAG, "Created channel id=$id (${spec.internalProviderId})")
        return id
    }

    private fun tagChannel(channelId: Long, internalProviderId: String) {
        val values = ContentValues().apply {
            put(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, internalProviderId)
        }
        context.contentResolver.update(TvContractCompat.buildChannelUri(channelId), values, null, null)
        Log.d(TAG, "Tagged channel $channelId as $internalProviderId")
    }

    /**
     * Deletes our preview channels that no row uses: copies left by a creation race, or by an
     * older build after the stored id was lost. They would otherwise sit on the home screen
     * with stale items. The provider deletes their programs with them.
     */
    private fun deleteStrayChannels(channels: List<ProviderChannel>, keep: Set<Long>) {
        channels
            .filter { it.id !in keep }
            .filter { it.packageName == context.packageName && it.type == TvContractCompat.Channels.TYPE_PREVIEW }
            .forEach { channel ->
                context.contentResolver.delete(TvContractCompat.buildChannelUri(channel.id), null, null)
                Log.d(TAG, "Deleted stray channel ${channel.id} (${channel.internalProviderId})")
            }
    }

    /**
     * Syncs [items] to the Continue Watching channel: upserts present items and
     * removes rows that are no longer in the list (completed or dismissed).
     */
    suspend fun reconcile(items: List<WatchProgress>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = ensureChannel() ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = items.map { progressKey(it) }.toSet()

            for ((key, rowIds) in existing) {
                if (key !in desiredKeys) {
                    rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId), null, null
                        )
                        Log.d(TAG, "Removed program key=$key rowId=$rowId")
                    }
                    syncedProgramFingerprints.remove(key)
                }
            }

            items.forEachIndexed { index, progress ->
                val key = progressKey(progress)
                val rowIds = existing[key]

                val (imageUri, _) = when {
                    !progress.backdrop.isNullOrBlank() -> progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    !progress.poster.isNullOrBlank() -> progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    else -> null to null
                }
                val positionMs = if (progress.position > 0) {
                    progress.position.toInt()
                } else {
                    (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
                }

                val newFingerprint = ChannelProgramFingerprint(
                    title = progress.name,
                    position = positionMs,
                    duration = progress.duration.toInt(),
                    imageUri = imageUri,
                    logoUri = progress.logo,
                    sortOrder = index,
                    season = progress.season,
                    episode = progress.episode,
                    lastEngagementTime = progress.lastWatched
                )
                val oldFingerprint = syncedProgramFingerprints[key]

                if (!rowIds.isNullOrEmpty() && oldFingerprint != null &&
                    oldFingerprint.title == newFingerprint.title &&
                    oldFingerprint.imageUri == newFingerprint.imageUri &&
                    oldFingerprint.logoUri == newFingerprint.logoUri &&
                    oldFingerprint.sortOrder == newFingerprint.sortOrder &&
                    oldFingerprint.season == newFingerprint.season &&
                    oldFingerprint.episode == newFingerprint.episode &&
                    oldFingerprint.duration == newFingerprint.duration &&
                    abs((oldFingerprint.position ?: 0) - (newFingerprint.position ?: 0)) < 2_000
                ) {
                    // Item already in sync, skip database update to prevent waking up launcher
                    return@forEachIndexed
                }

                val values = buildProgramValues(progress, channelId, index, key)
                if (!rowIds.isNullOrEmpty()) {
                    val primaryRowId = rowIds.first()
                    // UPDATE the existing program row in place — keeping its row ID stable.
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(primaryRowId), values, null, null
                    )
                    // If there are duplicate rows for the same key, delete them to clean up the database.
                    if (rowIds.size > 1) {
                        rowIds.drop(1).forEach { extraRowId ->
                            context.contentResolver.delete(
                                TvContractCompat.buildPreviewProgramUri(extraRowId), null, null
                            )
                            Log.d(TAG, "Removed duplicate program key=$key rowId=$extraRowId")
                        }
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI, values
                    )
                }
                syncedProgramFingerprints[key] = newFingerprint
                Log.d(TAG, "${if (!rowIds.isNullOrEmpty()) "Updated" else "Inserted"} program key=$key pos=${values.getAsInteger("last_playback_position_millis")} dur=${values.getAsInteger("duration_millis")} pct=${progress.progressPercent}")
            }
        }.onFailure { Log.w(TAG, "reconcile failed", it) }
    }

    /** Removes all preview programs from our channel (used on sign-out / history clear). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = prefs.getChannelId() ?: return@runCatching
            val rows = queryExistingPrograms(channelId)
            var deletedCount = 0
            rows.values.flatten().forEach { rowId ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(rowId), null, null
                )
                deletedCount++
            }
            syncedProgramFingerprints.clear()
            Log.d(TAG, "Cleared $deletedCount programs for channel $channelId")
        }.onFailure { Log.w(TAG, "clearAll failed", it) }
    }

    /**
     * Syncs [items] to the Library channel, in the order given: upserts present items and
     * removes rows for items no longer in the library. An empty list leaves an empty row.
     */
    suspend fun reconcileLibrary(items: List<LibraryEntry>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = ensureLibraryChannel() ?: return@runCatching
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = items.map { libraryKey(it) }.toSet()

            for ((key, rowIds) in existing) {
                if (key !in desiredKeys) {
                    rowIds.forEach { rowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(rowId), null, null
                        )
                    }
                    syncedLibraryFingerprints.remove(key)
                    Log.d(TAG, "Removed library program key=$key")
                }
            }

            items.forEachIndexed { index, entry ->
                val key = libraryKey(entry)
                val rowIds = existing[key]
                // Rewrite only when something visible changed, to avoid waking the launcher.
                val fingerprint = LibraryProgramFingerprint(entry, index)
                if (!rowIds.isNullOrEmpty() && syncedLibraryFingerprints[key] == fingerprint) {
                    return@forEachIndexed
                }
                val values = buildLibraryProgramValues(entry, channelId, index, key)
                if (!rowIds.isNullOrEmpty()) {
                    context.contentResolver.update(
                        TvContractCompat.buildPreviewProgramUri(rowIds.first()), values, null, null
                    )
                    rowIds.drop(1).forEach { extraRowId ->
                        context.contentResolver.delete(
                            TvContractCompat.buildPreviewProgramUri(extraRowId), null, null
                        )
                    }
                } else {
                    context.contentResolver.insert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI, values
                    )
                }
                syncedLibraryFingerprints[key] = fingerprint
                Log.d(TAG, "${if (!rowIds.isNullOrEmpty()) "Updated" else "Inserted"} library program key=$key")
            }
        }.onFailure { Log.w(TAG, "reconcileLibrary failed", it) }
    }

    /** Removes all preview programs from the Library channel (used on sign-out). */
    suspend fun clearLibrary() = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = prefs.getLibraryChannelId() ?: return@runCatching
            val rowIds = queryExistingPrograms(channelId).values.flatten()
            rowIds.forEach { rowId ->
                context.contentResolver.delete(
                    TvContractCompat.buildPreviewProgramUri(rowId), null, null
                )
            }
            syncedLibraryFingerprints.clear()
            Log.d(TAG, "Cleared ${rowIds.size} programs for library channel $channelId")
        }.onFailure { Log.w(TAG, "clearLibrary failed", it) }
    }

    // ---

    private fun queryExistingPrograms(channelId: Long): Map<String, List<Long>> {
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
        )
        val result = mutableMapOf<String, MutableList<Long>>()
        // Fire OS rejects any selection clause on preview_program URIs with SecurityException;
        // the provider auto-scopes to the calling package, so we filter channelId in memory.
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIdx = c.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (c.moveToNext()) {
                if (c.getLong(channelIdx) != channelId) continue
                val key = c.getString(keyIdx) ?: continue
                result.getOrPut(key) { mutableListOf() }.add(c.getLong(idIdx))
            }
        }
        return result
    }

    private fun buildProgramValues(
        progress: WatchProgress,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                putExtra("videoId", progress.videoId)
                putExtra("name", progress.name)
                putExtra("poster", progress.poster)
                putExtra("backdrop", progress.backdrop)
                putExtra("logo", progress.logo)
                progress.season?.let { putExtra("season", it) }
                progress.episode?.let { putExtra("episode", it) }
                progress.episodeTitle?.let { putExtra("episodeTitle", it) }
                putExtra("launchMode", "stream")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (progress.contentType.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(progress.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        // Backdrop/poster fills the tile via posterArt; logo goes to the dedicated logo column
        // so the launcher renders it as a small badge overlay on focus.
        val (imageUri, aspectRatio) = when {
            !progress.backdrop.isNullOrBlank() ->
                progress.backdrop to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !progress.poster.isNullOrBlank() ->
                progress.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        progress.logo?.let { builder.setLogoUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            val positionMs = if (progress.position > 0) {
                progress.position.toInt()
            } else {
                (progress.progressPercent?.let { it / 100f * progress.duration }?.toLong() ?: 0L).toInt()
            }
            builder.setLastPlaybackPositionMillis(positionMs)
        } else if (progress.progressPercent != null && progress.progressPercent > 0f) {
            // No real duration known (e.g. Simkl/Trakt sync), but we have a percent.
            // Use synthetic values so the launcher can render a progress bar.
            val syntheticDuration = 100_000
            val syntheticPosition = (progress.progressPercent / 100f * syntheticDuration).toInt()
            builder.setDurationMillis(syntheticDuration)
            builder.setLastPlaybackPositionMillis(syntheticPosition)
        }

        if (type == TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build().toContentValues().also {
            // COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS drives launcher ordering;
            // the Builder method was added after tvprovider 1.0.0, so set directly.
            it.put("last_engagement_time_utc_millis", progress.lastWatched)
            // Explicitly clear poster art when no image is available, so UPDATE operations
            // don't leave stale artwork from previous reconcile cycles.
            if (imageUri == null) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }
            if (progress.logo.isNullOrBlank()) {
                it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            }
            // Clear duration/position when unknown so stale values (e.g. previous 1hr fallback)
            // don't persist across UPDATE cycles.
            if (progress.duration <= 0 && (progress.progressPercent == null || progress.progressPercent <= 0f)) {
                it.putNull("duration_millis")
                it.putNull("last_playback_position_millis")
            }
        }
    }

    private fun buildLibraryProgramValues(
        entry: LibraryEntry,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        // No videoId/name extras: MainActivity opens the details page instead of the stream picker.
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", entry.id)
                putExtra("contentType", entry.type)
                entry.addonBaseUrl?.let { putExtra("addonBaseUrl", it) }
                putExtra("launchMode", "detail")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )

        val type = if (entry.type.equals("movie", ignoreCase = true))
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        else
            TvContractCompat.PreviewPrograms.TYPE_TV_SERIES

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type)
            .setTitle(entry.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)

        val (imageUri, aspectRatio) = when {
            !entry.poster.isNullOrBlank() -> entry.poster to when (entry.posterShape) {
                PosterShape.LANDSCAPE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                PosterShape.SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
                PosterShape.POSTER -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            }
            !entry.background.isNullOrBlank() ->
                entry.background to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        entry.background?.takeIf { it.isNotBlank() }?.let { builder.setThumbnailUri(Uri.parse(it)) }
        entry.logo?.takeIf { it.isNotBlank() }?.let { builder.setLogoUri(Uri.parse(it)) }
        entry.description?.takeIf { it.isNotBlank() }?.let { builder.setDescription(it) }
        entry.releaseInfo?.takeIf { it.isNotBlank() }?.let { builder.setReleaseDate(it) }

        return builder.build().toContentValues().also {
            // Clear optional columns so an UPDATE doesn't keep values from an older version.
            if (imageUri == null) it.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            if (entry.background.isNullOrBlank()) it.putNull(TvContractCompat.PreviewPrograms.COLUMN_THUMBNAIL_URI)
            if (entry.logo.isNullOrBlank()) it.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
            if (entry.description.isNullOrBlank()) it.putNull(TvContractCompat.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
            if (entry.releaseInfo.isNullOrBlank()) it.putNull(TvContractCompat.PreviewPrograms.COLUMN_RELEASE_DATE)
        }
    }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
                ?: return
            context.contentResolver.openOutputStream(
                TvContractCompat.buildChannelLogoUri(channelId)
            )?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "writeChannelLogo failed", it) }
    }

    /**
     * Mirrors WatchProgressPreferences.createKey() so channel and storage agree on identity.
     */
    private fun progressKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null)
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        else
            progress.contentId
}

private fun libraryKey(entry: LibraryEntry): String = "${entry.type}:${entry.id}"
