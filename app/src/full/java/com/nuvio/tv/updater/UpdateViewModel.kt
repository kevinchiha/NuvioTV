package com.nuvio.tv.updater

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSec: Long = 0L,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showNoUpdateToastHint: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    init {
        // Lightweight check on app start.
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, errorMessage = null, showNoUpdateToastHint = false) }

            val ignoredVersionCode = updatePreferences.ignoredVersionCode.first()

            val result = updateRepository.getLatestUpdate()
            updatePreferences.setLastCheckAtMs(System.currentTimeMillis())

            result
                .onSuccess { update ->
                    val remoteNewer = update.versionCode > BuildConfig.VERSION_CODE
                    val shouldShow = remoteNewer && (ignoredVersionCode == null || ignoredVersionCode != update.versionCode)

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = update,
                            isUpdateAvailable = remoteNewer,
                            showDialog = shouldShow || force,
                            showNoUpdateToastHint = showNoUpdateFeedback && !remoteNewer,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            update = null,
                            isUpdateAvailable = false,
                            showDialog = force, // show error dialog if user forced a check
                            errorMessage = e.message ?: context.getString(R.string.update_error_check_failed)
                        )
                    }
                }
        }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showDialog = false, showUnknownSourcesDialog = false, errorMessage = null) }
    }

    fun ignoreThisVersion() {
        viewModelScope.launch {
            val versionCode = _uiState.value.update?.versionCode
            updatePreferences.setIgnoredVersionCode(versionCode)
            _uiState.update { it.copy(showDialog = false) }
        }
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = null,
                    bytesPerSec = 0L,
                    errorMessage = null
                )
            }

            val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(File(context.cacheDir, "updates"), safeName)

            val startMs = System.currentTimeMillis()

            val result = withContext(Dispatchers.IO) {
                apkDownloader.download(update.assetUrl, dest) { downloaded, total ->
                    val progress = if (total != null && total > 0) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    // bytes/sec = downloaded * 1000 / elapsedMs (kevbox MainViewModel model)
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val bps = downloaded * 1000L / elapsed
                    _uiState.update {
                        it.copy(
                            downloadProgress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            bytesPerSec = bps
                        )
                    }
                }
            }

            result
                .onSuccess { file ->
                    // Verify SHA-256 before exposing the install action — reject on mismatch.
                    val expected = update.sha256
                    val verified = withContext(Dispatchers.IO) {
                        runCatching { Checksum.verify(file, expected) }.getOrDefault(false)
                    }
                    if (!verified) {
                        // SHA-256 mismatch: the downloaded APK is untrustworthy — reject install.
                        // Reuses the existing download-failed string (a dedicated
                        // `update_error_checksum_failed` resource is a follow-up for the
                        // resource owner, since string files are owned by another workstream).
                        runCatching { file.delete() }
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                bytesPerSec = 0L,
                                downloadedApkPath = null,
                                errorMessage = context.getString(R.string.update_error_download_failed)
                            )
                        }
                        return@launch
                    }

                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            bytesPerSec = 0L,
                            downloadedApkPath = file.absolutePath,
                            errorMessage = null
                        )
                    }
                    // Auto-start installation flow immediately after successful download + verify.
                    // If unknown sources permission is missing, this will surface the settings prompt.
                    installUpdateOrRequestPermission()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgress = null,
                            bytesPerSec = 0L,
                            downloadedApkPath = null,
                            errorMessage = e.message ?: context.getString(R.string.update_error_download_failed)
                        )
                    }
                }
        }
    }

    fun installUpdateOrRequestPermission() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.update_error_apk_missing)) }
            return
        }

        if (!ApkInstaller.canRequestPackageInstalls(context)) {
            _uiState.update { it.copy(showUnknownSourcesDialog = true) }
            return
        }

        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        ApkInstaller.launchInstall(context, apkFile)
    }

    fun openUnknownSourcesSettings() {
        ApkInstaller.buildUnknownSourcesSettingsIntent(context)?.let { intent ->
            context.startActivity(intent)
        }
    }
}
