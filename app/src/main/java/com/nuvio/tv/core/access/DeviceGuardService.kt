package com.nuvio.tv.core.access

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.DeviceGuardDataStore
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceGuardService"

/**
 * Enforces the one-device-per-member limit (MEMBER-ACCESS-PLAN.md Extension). Sibling of
 * [AccessControlService]; shares [AccessControl] grace logic and the same never-throw discipline.
 *
 * On each [refreshDeviceClaim] the app calls the server `claim_device` RPC with this install's
 * stable device id:
 *  - `true`  → AUTHORIZED: record success, clear the lock.
 *  - `false` → DENIED (over the member's device cap): lock, but do NOT record success (so a later
 *    `max_devices` bump while offline doesn't sit in a fresh grace window).
 *  - exception (offline / JWT / unauthenticated) → UNKNOWN: the lock is monotonic — it can only go
 *    false→true via grace expiry, and is only cleared by a successful AUTHORIZED claim.
 *
 * The RPC returns a scalar Boolean, so there is no `@Serializable` model and no R8 keep rules.
 */
@Singleton
class DeviceGuardService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val deviceGuardDataStore: DeviceGuardDataStore
) {
    // KevBox upstream-sync note: 0.7.16 reverted the 0.7.9 dbswitch — SupabaseModule is back and
    // @Provides Postgrest directly, so we inject Postgrest again. See MemberConfigService for rationale.

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _deviceLockedOut = MutableStateFlow(false)
    /** True when this device is locked out (server denied the claim, or offline grace expired). */
    val deviceLockedOut: StateFlow<Boolean> = _deviceLockedOut.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    /** Flips true after the first persisted read lands, so the gate can hold a loading box. */
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    init {
        // Seed the lock state from disk BEFORE first render so a previously-denied device doesn't
        // flash a frame of full content at cold start. Mirrors AccessControlService.
        scope.launch {
            try {
                val snapshot = deviceGuardDataStore.snapshot()
                _deviceLockedOut.value = snapshot.deviceLockedOut
            } catch (e: Exception) {
                Log.e(TAG, "Failed to seed device lock state from DataStore", e)
            } finally {
                _initialized.value = true
            }
        }
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Re-evaluates this device's claim against the server. MUST NEVER THROW — an escaped exception
     * from the polling [androidx.compose.runtime.LaunchedEffect] body would cancel the poller for
     * the rest of the session and silently freeze the device limit. All work runs on
     * [Dispatchers.IO].
     */
    suspend fun refreshDeviceClaim() = withContext(Dispatchers.IO) {
        try {
            // Must be signed in with the member's OWN auth.uid() (never the effective/sync-owner id).
            // The RPC resolves auth.uid() server-side; we only use this as the signed-in gate.
            if (authManager.currentUserId == null) {
                applyUnknown()
                return@withContext
            }

            val deviceId = deviceGuardDataStore.getOrCreateDeviceId()
            val name = "${Build.MANUFACTURER} ${Build.MODEL}"

            val ok = try {
                withJwtRefreshRetry {
                    postgrest.rpc(
                        "claim_device",
                        buildJsonObject {
                            put("p_device_id", deviceId)
                            put("p_device_name", name)
                        }
                    ).decodeAs<Boolean>()
                }
            } catch (e: Exception) {
                // Network / JWT / 401 / unauthenticated → UNKNOWN (grace).
                Log.w(TAG, "claim_device failed, routing to grace", e)
                applyUnknown()
                return@withContext
            }

            if (ok) {
                // AUTHORIZED: stamp both grace clocks and clear the lock.
                deviceGuardDataStore.recordSuccess(
                    wallMs = System.currentTimeMillis(),
                    elapsedMs = SystemClock.elapsedRealtime()
                )
                deviceGuardDataStore.setDeviceLockedOut(false)
                _deviceLockedOut.value = false
            } else {
                // DENIED (over the limit): lock, do NOT record success.
                deviceGuardDataStore.setDeviceLockedOut(true)
                _deviceLockedOut.value = true
            }
        } catch (e: Throwable) {
            // Catch-all: refreshDeviceClaim MUST NEVER THROW.
            Log.e(TAG, "refreshDeviceClaim failed unexpectedly", e)
        } finally {
            _initialized.value = true
        }
    }

    /**
     * UNKNOWN verdict (couldn't reach / authenticate against the server). Locks only if the offline
     * grace window since the last successful claim has expired; a never-claimed device stays
     * in-grace. The lock is monotonic — this never clears an already-set lock.
     */
    private suspend fun applyUnknown() {
        try {
            val snapshot = deviceGuardDataStore.snapshot()
            // Already locked stays locked (monotonic).
            if (snapshot.deviceLockedOut) {
                _deviceLockedOut.value = true
                return
            }
            val expired = AccessControl.graceExpired(
                lastOkWallMs = snapshot.lastOkWallMs,
                lastOkElapsedMs = snapshot.lastOkElapsedMs,
                nowWallMs = System.currentTimeMillis(),
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
            if (expired) {
                deviceGuardDataStore.setDeviceLockedOut(true)
                _deviceLockedOut.value = true
            }
            // else: still in grace — leave deviceLockedOut as-is (never cleared here).
        } catch (e: Exception) {
            Log.e(TAG, "applyUnknown grace evaluation failed", e)
        }
    }
}
