package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import com.nuvio.tv.updater.model.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the self-hosted update manifest from `${UPDATE_BASE_URL}/version.json` (persovps,
 * kevbox-support model) and maps it into the dialog-facing [AppUpdate]. No GitHub Releases.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun getLatestUpdate(): Result<AppUpdate> {
        return runCatching {
            val url = BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/version.json"

            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            val (raw, dateHeader) = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Update manifest error: HTTP ${response.code}")
                    }
                    val body = response.body?.string() ?: error("Empty update manifest response")
                    body to response.header("Date")
                }
            }

            parseManifest(raw, Build.VERSION.SDK_INT).copy(serverTimeMs = parseHttpDate(dateHeader))
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * The epoch ms of an HTTP `Date` header (RFC 1123), or null when [header] is missing,
         * blank or unreadable. Pure so it can be unit-tested.
         */
        fun parseHttpDate(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            return runCatching {
                ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }

        /**
         * Parses `version.json` and maps it to the dialog-facing [AppUpdate], picking the
         * install-time compile profile that matches [sdkInt]. Pure so it can be unit-tested.
         */
        fun parseManifest(raw: String, sdkInt: Int): AppUpdate {
            val manifest = json.decodeFromString(UpdateManifest.serializer(), raw)

            if (manifest.url.isBlank()) error("Update manifest has no APK url")
            if (manifest.sha256.isBlank()) error("Update manifest has no sha256")

            val assetName = manifest.url.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: "kevbox-tv-${manifest.versionName}.apk"
            val profile = InstallProfile.pick(manifest.profiles, sdkInt)
            val silent = SilentBlock.parse(manifest.silent)

            // Map manifest -> AppUpdate: versionName drives the dialog's version tag/title and
            // notes drives the dialog's markdown release-notes section (kept non-blank).
            return AppUpdate(
                versionCode = manifest.versionCode,
                tag = manifest.versionName,
                title = manifest.versionName,
                notes = manifest.notes,
                sha256 = manifest.sha256,
                releaseUrl = null,
                assetName = assetName,
                assetUrl = manifest.url,
                assetSizeBytes = null,
                profileUrl = profile?.url,
                profileSha256 = profile?.sha256,
                silentAfterMs = silent?.afterMs,
                silentEarlyUsers = silent?.earlyUsers ?: emptyList()
            )
        }
    }
}
