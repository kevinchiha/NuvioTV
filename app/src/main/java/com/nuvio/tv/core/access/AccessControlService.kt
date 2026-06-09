package com.nuvio.tv.core.access

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.AccessControlDataStore
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AccessControlService"

/**
 * Drives the KevBox TV access kill-switch: periodically asks Supabase whether this member is still
 * allowed, and flips [lockedOut] so the UI can swap in a full-screen lock.
 *
 * The verdict is read through the `get_access_verdict` SECURITY DEFINER RPC, which resolves
 * `auth.uid()` server-side and **raises** on an unauthenticated caller — so an expired/stale JWT
 * surfaces as an exception (→ grace), never a fail-open allow. We pass **no** user id and never use
 * `getEffectiveUserId` (that is the sync owner, not the JWT subject).
 *
 * [refreshAccess] is the only entry point; it MUST NEVER THROW (a thrown exception escaping a
 * `LaunchedEffect` body would kill the poller for the rest of the session).
 */
@Singleton
class AccessControlService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val accessControlDataStore: AccessControlDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lockedOut = MutableStateFlow(false)
    /** True when the member is locked out (server `LOCKED`, or offline grace expired). */
    val lockedOut: StateFlow<Boolean> = _lockedOut.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    /** Flips true after the first persisted read lands, so the gate can hold a loading box. */
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    init {
        // Seed lockedOut from the persisted value BEFORE first render so a previously-locked member
        // never flashes a frame of full content at cold start.
        scope.launch {
            try {
                val snapshot = accessControlDataStore.snapshot()
                _lockedOut.value = snapshot.lockedOut
            } catch (e: Exception) {
                Log.e(TAG, "Failed to seed locked-out state from DataStore", e)
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
     * Ask the server for the current verdict and update [lockedOut].
     *
     * Verdict mapping:
     *  - `ALLOWED` / `NO_ROW` → record success (stamp both clocks), `lockedOut = false`.
     *  - `LOCKED`            → `lockedOut = true` (do NOT record success, so a later offline
     *                          re-enable doesn't sit in a fresh 5-min grace).
     *  - exception / unknown → UNKNOWN: [lockedOut] is **monotonic** here (never cleared); lock only
     *                          if [AccessControl.graceExpired] is true (never-checked stays in-grace).
     *
     * NEVER THROWS. All network/DataStore work runs on [Dispatchers.IO].
     */
    suspend fun refreshAccess() = withContext(Dispatchers.IO) {
        try {
            // Must be signed in (member's own JWT) to even ask. Otherwise route to grace.
            if (authManager.currentUserId == null) {
                applyUnknown()
                return@withContext
            }

            val verdict = try {
                withJwtRefreshRetry {
                    postgrest.rpc("get_access_verdict").decodeAs<String>()
                }
            } catch (e: Exception) {
                // Network / JWT / 401 / server `not authenticated` raise → UNKNOWN (grace).
                Log.w(TAG, "get_access_verdict failed, routing to grace", e)
                applyUnknown()
                return@withContext
            }

            when (verdict) {
                "ALLOWED", "NO_ROW" -> {
                    accessControlDataStore.recordSuccess(
                        wallMs = System.currentTimeMillis(),
                        elapsedMs = SystemClock.elapsedRealtime()
                    )
                    accessControlDataStore.setLockedOut(false)
                    _lockedOut.value = false
                }
                "LOCKED" -> {
                    // Do NOT record success.
                    accessControlDataStore.setLockedOut(true)
                    _lockedOut.value = true
                }
                else -> {
                    // Unexpected verdict string → treat conservatively as UNKNOWN (grace).
                    Log.w(TAG, "Unexpected access verdict: $verdict")
                    applyUnknown()
                }
            }
        } catch (e: Throwable) {
            // Catch-all: refreshAccess MUST NEVER THROW.
            Log.e(TAG, "refreshAccess failed unexpectedly", e)
        } finally {
            _initialized.value = true
        }
    }

    /**
     * UNKNOWN path: the lock is monotonic (it can only go false → true here, never cleared).
     * Computes [AccessControl.graceExpired] from the persisted clocks and the current clocks; locks
     * when grace has expired. A device that has never had a successful check (both clocks `0L`) stays
     * in-grace, so a first launch while offline isn't a false lockout.
     */
    private suspend fun applyUnknown() {
        try {
            val snapshot = accessControlDataStore.snapshot()
            // Already locked stays locked (monotonic).
            if (snapshot.lockedOut) {
                _lockedOut.value = true
                return
            }
            val expired = AccessControl.graceExpired(
                lastOkWallMs = snapshot.lastOkWallMs,
                lastOkElapsedMs = snapshot.lastOkElapsedMs,
                nowWallMs = System.currentTimeMillis(),
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
            if (expired) {
                accessControlDataStore.setLockedOut(true)
                _lockedOut.value = true
            }
            // else: still in grace — leave lockedOut as-is (never cleared here).
        } catch (e: Exception) {
            Log.e(TAG, "applyUnknown grace evaluation failed", e)
        }
    }
}
