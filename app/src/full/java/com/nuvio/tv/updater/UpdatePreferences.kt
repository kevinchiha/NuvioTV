package com.nuvio.tv.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler // KevBox: KEVBOX-TV-5
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences // KevBox: KEVBOX-TV-5
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// KevBox: a broken file (all zero bytes after a power cut or a kill mid-save) resets to empty
// instead of throwing on every read, which crash-looped the app until a data clear (KEVBOX-TV-5).
private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "update_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.updateDataStore

    // Now keyed off the manifest versionCode (was a string release tag) to match the
    // versionCode-based "is newer?" comparison.
    private val ignoredVersionCodeKey = intPreferencesKey("ignored_version_code")
    private val lastCheckAtKey = longPreferencesKey("last_check_at_ms")

    val ignoredVersionCode: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[ignoredVersionCodeKey]
    }

    val lastCheckAtMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastCheckAtKey] ?: 0L
    }

    suspend fun setIgnoredVersionCode(versionCode: Int?) {
        dataStore.edit { prefs ->
            if (versionCode == null) prefs.remove(ignoredVersionCodeKey) else prefs[ignoredVersionCodeKey] = versionCode
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        dataStore.edit { prefs ->
            prefs[lastCheckAtKey] = value
        }
    }

    private val predownloadApkPathKey = stringPreferencesKey("predownloaded_apk_path")
    private val predownloadUpdateJsonKey = stringPreferencesKey("predownloaded_update_json")

    /** Absolute path of a background-downloaded, SHA-256-verified APK (or null). */
    val predownloadApkPath: Flow<String?> = dataStore.data.map { prefs ->
        prefs[predownloadApkPathKey]
    }

    /** Serialized [com.nuvio.tv.updater.model.AppUpdate] matching [predownloadApkPath] (or null). */
    val predownloadUpdateJson: Flow<String?> = dataStore.data.map { prefs ->
        prefs[predownloadUpdateJsonKey]
    }

    suspend fun setPredownload(apkPath: String, updateJson: String) {
        dataStore.edit { prefs ->
            prefs[predownloadApkPathKey] = apkPath
            prefs[predownloadUpdateJsonKey] = updateJson
        }
    }

    /** Clears the pre-download pointers. The caller deletes the on-disk APK (no file handle here). */
    suspend fun clearPredownload() {
        dataStore.edit { prefs ->
            prefs.remove(predownloadApkPathKey)
            prefs.remove(predownloadUpdateJsonKey)
        }
    }

    // Silent-install state for one versionCode (AC-8). A new versionCode starts over.
    private val silentVersionCodeKey = intPreferencesKey("silent_version_code")
    private val silentAttemptsKey = intPreferencesKey("silent_attempts")
    private val silentRefusedKey = booleanPreferencesKey("silent_refused")

    /** The stored silent-install state, or null when none was saved. */
    val silentInstallState: Flow<SilentInstallState?> = dataStore.data.map(::readSilentState)

    /** Counts one silent attempt for [versionCode]. Called before the session is committed. */
    suspend fun recordSilentAttempt(versionCode: Int) = updateSilentState(versionCode, SilentStateEvent.ATTEMPT)

    /** Marks [versionCode] "silent refused": Android wanted the member to confirm it (AC-7). */
    suspend fun markSilentRefused(versionCode: Int) = updateSilentState(versionCode, SilentStateEvent.REFUSED)

    private suspend fun updateSilentState(versionCode: Int, event: SilentStateEvent) {
        dataStore.edit { prefs ->
            val next = nextSilentState(readSilentState(prefs), versionCode, event)
            prefs[silentVersionCodeKey] = next.versionCode
            prefs[silentAttemptsKey] = next.attempts
            prefs[silentRefusedKey] = next.refused
        }
    }

    private fun readSilentState(prefs: Preferences): SilentInstallState? {
        val versionCode = prefs[silentVersionCodeKey] ?: return null
        return SilentInstallState(versionCode, prefs[silentAttemptsKey] ?: 0, prefs[silentRefusedKey] ?: false)
    }
}
