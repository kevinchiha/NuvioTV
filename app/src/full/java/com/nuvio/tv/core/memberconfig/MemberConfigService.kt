package com.nuvio.tv.core.memberconfig

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.memberconfig.model.MemberAddonRow
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.repository.AddonRepository
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KevBox TV (full flavor) — remote per-member Stremio-addon config.
 *
 * Reads the member's rows from the Supabase `member_addon` table on app open / sign-in and mirrors
 * them onto the device's PRIMARY addon store (the set every inheriting sub-profile reads). The app
 * only ever READS this table; the operator edits it from the dashboard. See MEMBER-CONFIG-PLAN.md.
 *
 * Wired in (and kill-switch gated by [com.nuvio.tv.BuildConfig.FEATURE_MEMBER_ADDON_CONFIG]) from
 * the full [com.nuvio.tv.core.plugin.PluginManager]'s `init{}` via [start]. Not referenced by the
 * playstore flavor, so playstore/upstream are unaffected.
 *
 * Failure semantics: every apply is wrapped in try/catch and is non-fatal. On any failure the
 * device KEEPS its prior addon state (not necessarily the baked defaults) — nothing is wiped.
 */
@Singleton
class MemberConfigService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val addonRepository: AddonRepository,
    private val memberConfigPreferences: MemberConfigPreferences
) {
    // KevBox upstream-sync note: 0.7.9 deleted SupabaseModule and we routed this KevBox-only file
    // through SyncBackendSupabaseProvider (the remote "backend switch" client). 0.7.16 REVERTED that —
    // SupabaseModule is back and @Provides Postgrest directly, and the provider was deleted — so we
    // inject Postgrest directly again, like every upstream sync service. If a future sync re-deletes
    // SupabaseModule, re-introduce a provider indirection (grep SyncBackendSupabaseProvider history).

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Begin observing auth. Idempotent enough for a @Singleton (called once from PluginManager.init).
     * Applies whenever auth becomes [AuthState.FullAccount] (app open with a restored session, or a
     * fresh sign-in). No Realtime/websockets in v1 — apply-on-open only.
     */
    fun start() {
        scope.launch {
            authManager.authState
                .filterIsInstance<AuthState.FullAccount>()
                // Apply once per member, not on every auth re-emission (e.g. a transient
                // Loading -> FullAccount(same user) cycle on token refresh). A real account
                // switch changes userId and still re-applies.
                .distinctUntilChangedBy { it.userId }
                .collect { state -> applyForMember(state.userId) }
        }
    }

    private suspend fun applyForMember(userId: String) {
        try {
            // RLS re-checks server-side that this JWT may only read its own rows.
            val rows: List<MemberAddonRow> = withJwtRefreshRetry {
                postgrest.from("member_addon")
                    .select {
                        filter { eq("user_id", userId) }
                        order("sort_order", Order.ASCENDING)
                        order("id", Order.ASCENDING) // deterministic tiebreaker (Gap J)
                    }
                    .decodeList<MemberAddonRow>()
            }

            val changedUser = userId != memberConfigPreferences.lastAppliedUserId()
            when {
                rows.isNotEmpty() -> {
                    addonRepository.applyRemoteAddonConfig(
                        orderedUrls = rows.map { it.url },
                        enabledByUrl = rows.associate { it.url to it.enabled }
                    )
                    Log.i(TAG, "Applied ${rows.size} member_addon row(s) for $userId")
                }
                changedUser -> {
                    // Shared-TV account switch with zero rows → don't inherit the previous member's
                    // list; reset the primary store to the baked defaults (Gap K).
                    addonRepository.resetPrimaryAddonsToDefaults()
                    Log.i(TAG, "No member_addon rows for new member $userId; reset primary addons to defaults")
                }
                else -> {
                    // Same member, no rows: safety fallback — keep whatever is installed (baked defaults).
                    Log.i(TAG, "No member_addon rows for $userId; keeping current addons (empty-keep)")
                }
            }
            memberConfigPreferences.setLastAppliedUserId(userId)
        } catch (e: Exception) {
            // Non-fatal: keep prior addon state. Message distinguishes the cause (network / JWT / decode).
            Log.e(TAG, "member_addon apply failed for $userId, keeping prior addons: ${e.message}", e)
        }
    }

    /**
     * Retry a Supabase call once after a JWT refresh. Copied verbatim from the sync services
     * (AddonSyncService etc.) — there is no shared helper; each service owns its own copy.
     */
    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    companion object {
        private const val TAG = "MemberConfigService"
    }
}
