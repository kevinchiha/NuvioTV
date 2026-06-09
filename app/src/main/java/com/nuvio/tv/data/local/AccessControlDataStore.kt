package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accessControlDataStore: DataStore<Preferences> by preferencesDataStore(name = "access_control")

/**
 * Persists the KevBox TV access kill-switch state for the signed-in member.
 *
 * On-disk file: `files/datastore/access_control.preferences_pb` (backup-excluded — see
 * AndroidManifest backup rules — so an old backup can't reset the grace clock).
 *
 * Stores the last-successful-check moment as **two clocks** so a user rolling the device wall
 * clock back can't extend the offline grace window forever:
 *  - [lastOkWallMs]  = `System.currentTimeMillis()`     (survives reboot, user-settable)
 *  - [lastOkElapsedMs] = `SystemClock.elapsedRealtime()` (monotonic while up, reset on reboot)
 * Both default to `0L` ("never checked"). [AccessControl.graceExpired] combines them.
 *
 * Persisting means the lockout/grace survives a force-quit (relaunch can't bypass it).
 */
@Singleton
class AccessControlDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.accessControlDataStore

    private val lastOkWallMsKey = longPreferencesKey("last_ok_wall_ms")
    private val lastOkElapsedMsKey = longPreferencesKey("last_ok_elapsed_ms")
    private val lockedOutKey = booleanPreferencesKey("locked_out")

    /** The persisted lockout flag. Defaults to false. */
    val lockedOut: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[lockedOutKey] ?: false
    }

    /** True once the first persisted read has landed (so the gate can hold a loading box). */
    val initialized: Flow<Boolean> = dataStore.data.map { true }

    /** Snapshot of the persisted state for [AccessControlService] to drive the grace decision. */
    data class Snapshot(
        val lastOkWallMs: Long,
        val lastOkElapsedMs: Long,
        val lockedOut: Boolean
    )

    /** One-shot read of the current persisted clocks + lockout flag. */
    suspend fun snapshot(): Snapshot {
        val prefs = dataStore.data.first()
        return Snapshot(
            lastOkWallMs = prefs[lastOkWallMsKey] ?: 0L,
            lastOkElapsedMs = prefs[lastOkElapsedMsKey] ?: 0L,
            lockedOut = prefs[lockedOutKey] ?: false
        )
    }

    /** Record a successful check: stamp both clocks. Callers clear [lockedOut] separately. */
    suspend fun recordSuccess(wallMs: Long, elapsedMs: Long) {
        dataStore.edit { prefs ->
            prefs[lastOkWallMsKey] = wallMs
            prefs[lastOkElapsedMsKey] = elapsedMs
        }
    }

    /** Set the persisted lockout flag. */
    suspend fun setLockedOut(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[lockedOutKey] = value
        }
    }
}
