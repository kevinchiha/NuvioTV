package com.nuvio.tv.core.memberconfig

import com.nuvio.tv.domain.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KevBox TV. Signing out wipes the addon store (clearAfterSignOut clears every profile-scoped
 * DataStore), so the member-addon config MUST be re-applied on the next sign-in — including when
 * the same person signs back in. Before this gate existed the service filtered SignedOut out of
 * the auth stream and de-duplicated on userId, so a sign-out / sign-in round trip inside one app
 * session looked like a duplicate and the addons never came back until the app was restarted.
 */
class MemberConfigApplyGateTest {

    @Test
    fun `same member signing back in after a sign-out applies again`() {
        val gate = MemberConfigApplyGate()

        assertEquals("member-1", gate.onAuthState(signedInAs("member-1")))
        assertNull("sign-out itself applies nothing", gate.onAuthState(AuthState.SignedOut))
        assertEquals(
            "the addon store was wiped by the sign-out, so this must re-apply",
            "member-1",
            gate.onAuthState(signedInAs("member-1"))
        )
    }

    @Test
    fun `a token refresh does not re-apply for the member already applied`() {
        val gate = MemberConfigApplyGate()
        gate.onAuthState(signedInAs("member-1"))

        assertNull(gate.onAuthState(AuthState.Loading))
        assertNull(gate.onAuthState(signedInAs("member-1")))
    }

    @Test
    fun `switching member on a shared TV applies for the new member`() {
        val gate = MemberConfigApplyGate()
        gate.onAuthState(signedInAs("member-1"))

        assertEquals("member-2", gate.onAuthState(signedInAs("member-2")))
    }

    private fun signedInAs(userId: String) =
        AuthState.FullAccount(userId = userId, email = "$userId@example.com")
}
