package com.nuvio.tv.core.auth.loginfile

import com.nuvio.tv.domain.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginFileAccountActionTest {

    @Test
    fun `signed out signs in only`() {
        assertEquals(
            LoginFileAccountAction.SignInOnly,
            loginFileAccountAction(AuthState.SignedOut, "customer@example.com"),
        )
    }

    @Test
    fun `same email with different case and spaces signs in only`() {
        val state = AuthState.FullAccount(userId = "u1", email = "Customer@Example.com")
        assertEquals(
            LoginFileAccountAction.SignInOnly,
            loginFileAccountAction(state, "  customer@EXAMPLE.com "),
        )
    }

    @Test
    fun `different email signs out first`() {
        val state = AuthState.FullAccount(userId = "u1", email = "someone.else@example.com")
        assertEquals(
            LoginFileAccountAction.SignOutThenSignIn,
            loginFileAccountAction(state, "customer@example.com"),
        )
    }

    @Test
    fun `still loading signs out first`() {
        assertEquals(
            LoginFileAccountAction.SignOutThenSignIn,
            loginFileAccountAction(AuthState.Loading, "customer@example.com"),
        )
    }
}
