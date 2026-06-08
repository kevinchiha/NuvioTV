package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import com.nuvio.tv.updater.model.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getLatestUpdate(): Result<AppUpdate> {
        return runCatching {
            val url = BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/version.json"

            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            val raw = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Update manifest error: HTTP ${response.code}")
                    }
                    response.body?.string() ?: error("Empty update manifest response")
                }
            }

            val manifest = json.decodeFromString(UpdateManifest.serializer(), raw)

            if (manifest.url.isBlank()) error("Update manifest has no APK url")
            if (manifest.sha256.isBlank()) error("Update manifest has no sha256")

            val assetName = manifest.url.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: "kevbox-tv-${manifest.versionName}.apk"

            // Map manifest -> AppUpdate: versionName drives the dialog's version tag/title and
            // notes drives the dialog's markdown release-notes section (kept non-blank).
            AppUpdate(
                versionCode = manifest.versionCode,
                tag = manifest.versionName,
                title = manifest.versionName,
                notes = manifest.notes,
                sha256 = manifest.sha256,
                releaseUrl = null,
                assetName = assetName,
                assetUrl = manifest.url,
                assetSizeBytes = null
            )
        }
    }
}
