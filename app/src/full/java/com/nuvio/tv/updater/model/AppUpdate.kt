package com.nuvio.tv.updater.model

import androidx.annotation.Keep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("notes") val notes: String = ""
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
    val assetSizeBytes: Long?
)
