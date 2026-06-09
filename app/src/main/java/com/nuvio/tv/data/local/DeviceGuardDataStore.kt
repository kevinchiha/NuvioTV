package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceGuardDataStore: DataStore<Preferences> by preferencesDataStore(name = "device_guard")

/**
 * Persisted state for the one-device-per-member limit (see MEMBER-ACCESS-PLAN.md Extension).
 *
 * Stores a stable per-install device UUID plus the same tamper-resistant grace clocks as
 * [AccessControlDataStore]:
 *  - [getOrCreateDeviceId] generates a UUID once and returns the stored value thereafter. Clearing
 *    app data regenerates it, which makes this a *new* device — now over the limit, so still locked
 *    out (can't self-bypass).
 *  - `lastOkWallMs` / `lastOkElapsedMs` (both default `0L` = "never claimed") feed
 *    `AccessControl.graceExpired(...)` so a rolled-back wall clock can't extend offline grace.
 *  - `deviceLockedOut` survives a force-quit so a relaunch can't bypass the lock.
 *
 * On-disk file: files/datastore/device_guard.preferences_pb — this MUST be backup-excluded
 * (AndroidManifest dataExtractionRules / fullBackupContent), otherwise restoring this TV's backup
 * onto a second TV clones the same `deviceId` and both pass `claim_device` as one device.
 */
@Singleton
class DeviceGuardDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.deviceGuardDataStore

    private val deviceIdKey = stringPreferencesKey("device_id")
    private val lastOkWallMsKey = longPreferencesKey("last_ok_wall_ms")
    private val lastOkElapsedMsKey = longPreferencesKey("last_ok_elapsed_ms")
    private val deviceLockedOutKey = booleanPreferencesKey("device_locked_out")

    /** Emits the persisted lock state; defaults to false ("not locked") until a claim is denied. */
    val deviceLockedOut: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[deviceLockedOutKey] ?: false
    }

    /** Emits true once the first persisted read has landed, so the gate can avoid a wrong default. */
    val initialized: Flow<Boolean> = dataStore.data.map { true }

    /**
     * Returns the stable per-install device id, generating and persisting a fresh
     * [UUID.randomUUID] the first time it's requested.
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = dataStore.data.first()[deviceIdKey]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            // Re-check inside edit to avoid racing two callers into two different ids.
            val current = prefs[deviceIdKey]
            if (current.isNullOrBlank()) prefs[deviceIdKey] = generated
        }
        return dataStore.data.first()[deviceIdKey] ?: generated
    }

    /** Snapshot of the grace clocks + lock state for [com.nuvio.tv.core.access.DeviceGuardService]. */
    suspend fun snapshot(): DeviceGuardSnapshot {
        val prefs = dataStore.data.first()
        return DeviceGuardSnapshot(
            lastOkWallMs = prefs[lastOkWallMsKey] ?: 0L,
            lastOkElapsedMs = prefs[lastOkElapsedMsKey] ?: 0L,
            deviceLockedOut = prefs[deviceLockedOutKey] ?: false
        )
    }

    /** Records a successful device claim: stamps both clocks and clears the lock. */
    suspend fun recordSuccess(wallMs: Long, elapsedMs: Long) {
        dataStore.edit { prefs ->
            prefs[lastOkWallMsKey] = wallMs
            prefs[lastOkElapsedMsKey] = elapsedMs
            prefs[deviceLockedOutKey] = false
        }
    }

    /** Sets the persisted device lock state. */
    suspend fun setDeviceLockedOut(locked: Boolean) {
        dataStore.edit { prefs ->
            prefs[deviceLockedOutKey] = locked
        }
    }
}

/** Immutable snapshot used by the service to evaluate grace off a single consistent read. */
data class DeviceGuardSnapshot(
    val lastOkWallMs: Long,
    val lastOkElapsedMs: Long,
    val deviceLockedOut: Boolean
)
