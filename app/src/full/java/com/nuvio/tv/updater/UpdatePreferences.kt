package com.nuvio.tv.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_settings")

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
}
