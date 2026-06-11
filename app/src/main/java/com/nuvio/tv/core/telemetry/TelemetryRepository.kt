package com.nuvio.tv.core.telemetry

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durations-only telemetry. NEVER sends content ids, titles, urls, or playback position.
 *
 * Fail-soft by contract: every RPC is wrapped so that a network/JWT/server failure is swallowed and
 * logged — telemetry must NEVER disrupt playback or auth. Mirrors [com.nuvio.tv.core.access.DeviceGuardService]'s
 * `postgrest.rpc(...).withJwtRefreshRetry` pattern. Writes are attributed server-side to the JWT's
 * `auth.uid()`; we only gate on [AuthManager.currentUserId] (the signed-in member's OWN account,
 * matching `DeviceGuardService` — NOT the sync-owner / effective id).
 */
@Singleton
class TelemetryRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
) {
    suspend fun heartbeat(deviceId: String, kind: String) = call("record_heartbeat") {
        buildJsonObject {
            put("p_device_id", deviceId)
            put("p_app_version", BuildConfig.VERSION_NAME)
            put("p_kind", kind)
        }
    }

    suspend fun error(deviceId: String, code: String, message: String) = call("record_error") {
        buildJsonObject {
            put("p_device_id", deviceId)
            put("p_app_version", BuildConfig.VERSION_NAME)
            put("p_detail", buildJsonObject {
                put("code", code)
                put("message", message.take(300))
            })
        }
    }

    private suspend fun call(rpc: String, params: () -> JsonObject) =
        withContext(Dispatchers.IO) {
            if (authManager.currentUserId == null) return@withContext
            try {
                withJwtRefreshRetry { postgrest.rpc(rpc, params()) }
            } catch (e: CancellationException) {
                throw e                                                 // never swallow cancellation (structured concurrency)
            } catch (e: Exception) {
                Log.w(TAG, "$rpc failed (telemetry is fail-soft)", e)   // swallow — never disrupt playback
            }
        }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }

    companion object { private const val TAG = "TelemetryRepository" }
}
