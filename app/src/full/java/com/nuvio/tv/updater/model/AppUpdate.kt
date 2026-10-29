package com.nuvio.tv.updater.model

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Self-hosted update manifest published to `${UPDATE_BASE_URL}/version.json`.
 *
 * KevBox TV mirrors the kevbox-support model: the host serves a single static JSON
 * describing the latest build. The shape is:
 *
 * ```json
 * {
 *   "versionCode": 1022,
 *   "versionName": "0.7.5-beta",
 *   "url": "https://tv.kevbox.dev/kevbox-tv-0.7.5-beta.apk",
 *   "sha256": "<64-hex>",
 *   "notes": "## What's new\n- ..."
 * }
 * ```
 *
 * Marked [Serializable] + [Keep] so a minified RELEASE (R8) build still parses it.
 */
@Keep
@Serializable
data class UpdateManifest(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("url") val url: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("notes") val notes: String = "",
    // Install-time compile profiles (.dm files), one per Android API range. Absent from
    // manifests written before 1.2.0-beta.7.1, hence the default.
    @SerialName("profiles") val profiles: List<ProfileAsset> = emptyList(),
    // Optional silent-install block, {"after": <epoch ms>, "earlyUsers": [...]}. Kept as a raw
    // element so a malformed block cannot fail the whole manifest; SilentBlock.parse reads it.
    @SerialName("silent") val silent: JsonElement? = null
)

/**
 * One `.dm` file published next to the APK. Installing it together with the APK lets Android
 * compile the app's hot code during the install, instead of running it in the interpreter
 * until the next idle maintenance pass (measured on a TCL TV: 3.7% vs 58% of main-thread time
 * in compiled code on the first launch after an update).
 */
@Keep
@Serializable
data class ProfileAsset(
    @SerialName("minApi") val minApi: Int,
    @SerialName("maxApi") val maxApi: Int,
    @SerialName("url") val url: String,
    @SerialName("sha256") val sha256: String
)

/**
 * UI-facing model the [com.nuvio.tv.updater.ui.UpdatePromptDialog] renders. Field names are
 * unchanged from the previous GitHub-backed implementation so the dialog (which renders a
 * version [tag] and markdown [notes]) needs no changes, but it now carries the manifest
 * [versionCode] (the "is newer?" key + ignore key) and the [sha256] used to verify the APK
 * before install.
 */
@Keep
@Serializable
data class AppUpdate(
    val versionCode: Int,
    val tag: String,
    val title: String,
    val notes: String,
    val sha256: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    // The profile picked for this device's Android version, or null. Defaults keep cached
    // JSON saved by older builds decodable.
    val profileUrl: String? = null,
    val profileSha256: String? = null,
    // The manifest's silent-install block, flattened. Null / empty when the manifest has no
    // valid block; the defaults also keep cached JSON from older builds decodable.
    val silentAfterMs: Long? = null,
    val silentEarlyUsers: List<String> = emptyList()
)
