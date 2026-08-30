package com.nuvio.tv.core.memberconfig

import com.nuvio.tv.domain.model.AuthState

/**
 * KevBox TV (full flavor). Decides which auth-state changes should trigger a member-addon apply.
 *
 * Split out of [MemberConfigService] so the rule is testable without a coroutine scope, a Supabase
 * client or a DataStore — see MemberConfigApplyGateTest.
 *
 * Two competing requirements meet here:
 *
 *  - **Don't re-apply on churn.** Auth re-emits `FullAccount` for the same member on token refresh
 *    (often via a transient `Loading`). Re-running the apply on each of those would hit Supabase
 *    and rewrite the addon store for no reason.
 *  - **Do re-apply after a sign-out.** Signing out wipes the addon store —
 *    `AuthManager.signOut()` / `handleUnexpectedSignedOut()` call
 *    `AccountLocalDataResetService.clearAfterSignOut()`, which clears every profile-scoped
 *    DataStore, and `addon_preferences` is not in the retained set. The member is then left on the
 *    baked-in defaults, none of which serves streams, so the next sign-in MUST re-apply even
 *    though the member has not changed.
 *
 * The original implementation only satisfied the first: it filtered `SignedOut` out of the stream
 * and de-duplicated on `userId`, so a sign-out / sign-in round trip inside one app session
 * collapsed into a duplicate and the addons never came back until the app restarted. Observed
 * 2026-08-30 on the emulator: "Applied 7 member_addon row(s)", session dropped 8 seconds later,
 * sign-in again, no apply. Treating `SignedOut` as a reset satisfies both.
 */
internal class MemberConfigApplyGate {

    private var lastAppliedUserId: String? = null

    /**
     * @return the member id to apply config for, or null when this state needs no work.
     */
    fun onAuthState(state: AuthState): String? = when (state) {
        // Whatever was applied is gone with the wiped store, so don't let the next sign-in
        // look like a duplicate.
        is AuthState.SignedOut -> {
            lastAppliedUserId = null
            null
        }
        // Transient (token refresh). Not a sign-out, so it must not reset the marker.
        is AuthState.Loading -> null
        is AuthState.FullAccount -> {
            if (state.userId == lastAppliedUserId) {
                null
            } else {
                lastAppliedUserId = state.userId
                state.userId
            }
        }
    }
}
