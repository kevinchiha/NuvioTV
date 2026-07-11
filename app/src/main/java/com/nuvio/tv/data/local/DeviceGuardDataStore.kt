// ═══════════════════════════════════════════════════════════════════════════════════════════════
// KevBox upstream-sync note (READ BEFORE MERGING UPSTREAM):
//
// This ENTIRE file is a KevBox-only addition — part of the one-device-per-member limit (see
// MEMBER-ACCESS-PLAN.md, added in the "kill-switch + one-device-limit" client commit). Upstream
// NuvioTV has NO counterpart, so upstream merges should never touch this file and it must not
// conflict. If upstream ever introduces its own device-identity / device-registration concept,
// reconcile it HERE instead of adding a second parallel mechanism.
//
// This file is only HALF of a feature that spans TWO repos — the halves MUST stay in sync:
//   • APP side (this file): chooses the `device_id` the TV sends to the server on every poll
//     (via DeviceGuardService.refreshDeviceClaim → claim_device RPC).
//   • SERVER side (kevbox repo): supabase/migrations/0003_member_device.sql defines the tables and
//     0009_claim_device_newest_wins.sql defines the live claim_device() — it decides allow / evict
//     for that id. How the id is chosen HERE directly changes how claim_device() behaves THERE.
//
// Design of the id itself: we prefer Settings.Secure.ANDROID_ID because it survives an app
// reinstall / "clear data" on the same TV (an app-local UUID does not), which is what previously
// caused "same TV, locked out" false positives. See [chooseDeviceId] + getOrCreateDeviceId below.
// ═══════════════════════════════════════════════════════════════════════════════════════════════
package com.nuvio.tv.data.local

import android.content.Context
import android.provider.Settings
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
 * Holds this TV's device id plus the same tamper-resistant grace clocks as [AccessControlDataStore]:
 *  - [getOrCreateDeviceId] prefers Settings.Secure.ANDROID_ID, which SURVIVES an app reinstall /
 *    "clear data" on the same TV, so a wiped install re-presents the SAME id and just re-confirms its
 *    slot instead of looking like a new device. (Random-UUID fallback for boxes with no usable
 *    ANDROID_ID; a legacy UUID already on disk is kept as-is.) On the rare case the id still changes,
 *    server-side newest-device-wins (migration 0009) evicts the stale row so the TV self-heals.
 *  - `lastOkWallMs` / `lastOkElapsedMs` (both default `0L` = "never claimed") feed
 *    `AccessControl.graceExpired(...)` so a rolled-back wall clock can't extend offline grace.
 *  - `deviceLockedOut` survives a force-quit so a relaunch can't bypass the lock.
 *
 * On-disk file: files/datastore/device_guard.preferences_pb — the grace clocks + lock flag MUST stay
 * backup-excluded (AndroidManifest dataExtractionRules / fullBackupContent) so a restored backup can't
 * clone a "last verified" state onto another TV. (The device id is now derived from ANDROID_ID, which
 * is not carried in the backup.)
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
     * Returns this TV's device id, persisting it on first use so it stays stable thereafter. Prefers
     * a reinstall-surviving [Settings.Secure.ANDROID_ID] (see [chooseDeviceId]); an already-stored id
     * (e.g. a legacy random UUID) is kept as-is.
     */
    suspend fun getOrCreateDeviceId(): String {
        val stored = dataStore.data.first()[deviceIdKey]
        val chosen = chooseDeviceId(stored, readAndroidId()) ?: UUID.randomUUID().toString()
        if (stored.isNullOrBlank()) {
            dataStore.edit { prefs ->
                // Re-check inside edit to avoid racing two callers into two different ids.
                if (prefs[deviceIdKey].isNullOrBlank()) prefs[deviceIdKey] = chosen
            }
            return dataStore.data.first()[deviceIdKey] ?: chosen
        }
        return chosen // stored present → chooseDeviceId returned it unchanged
    }

    /** Settings.Secure.ANDROID_ID (no permission needed); null/blank on the odd box → UUID fallback. */
    private fun readAndroidId(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

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

    // KevBox upstream-sync note: [chooseDeviceId] is pure + Android-free ON PURPOSE so it can be
    // unit-tested without Robolectric (see DeviceGuardDataStoreTest) and so the id policy is reviewed
    // in one place during an upstream merge. It is KevBox-only; upstream has no device id at all.
    companion object {
        // The well-known junk ANDROID_IDs: the pre-Froyo shared-bug constant, and all-zeros. Both are
        // treated as "no usable stable id" so we fall back to a random UUID rather than binding a value
        // that many devices share (which would let unrelated TVs collide on one device slot).
        private const val ANDROID_ID_BUGGY = "9774d56d682e549c"

        /**
         * Pure device-id policy (Android-free, unit-tested): keep an already-persisted [stored] id
         * for stability; else use a usable [androidId] (Settings.Secure.ANDROID_ID) so a reinstalled
         * TV keeps its id; else null → caller mints a random UUID (boxes with no usable ANDROID_ID).
         */
        fun chooseDeviceId(stored: String?, androidId: String?): String? {
            if (!stored.isNullOrBlank()) return stored
            val aid = androidId?.trim()
            if (aid.isNullOrBlank()) return null
            if (aid.equals(ANDROID_ID_BUGGY, ignoreCase = true)) return null
            if (aid.all { it == '0' }) return null
            return aid
        }
    }
}

/** Immutable snapshot used by the service to evaluate grace off a single consistent read. */
data class DeviceGuardSnapshot(
    val lastOkWallMs: Long,
    val lastOkElapsedMs: Long,
    val deviceLockedOut: Boolean
)
