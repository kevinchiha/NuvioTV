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
    fun `still loading with no saved session signs in only`() {
        assertEquals(
            LoginFileAccountAction.SignInOnly,
            loginFileAccountAction(AuthState.Loading, "customer@example.com", StoredSession.Absent),
        )
    }

    @Test
    fun `still loading with the same saved account signs in only`() {
        assertEquals(
            LoginFileAccountAction.SignInOnly,
            loginFileAccountAction(
                AuthState.Loading,
                " customer@EXAMPLE.com",
                StoredSession.WithEmail("Customer@Example.com "),
            ),
        )
    }

    @Test
    fun `still loading with another saved account signs out first`() {
        assertEquals(
            LoginFileAccountAction.SignOutThenSignIn,
            loginFileAccountAction(
                AuthState.Loading,
                "customer@example.com",
                StoredSession.WithEmail("someone.else@example.com"),
            ),
        )
    }

    @Test
    fun `still loading with a saved session of unknown account signs out first`() {
        assertEquals(
            LoginFileAccountAction.SignOutThenSignIn,
            loginFileAccountAction(AuthState.Loading, "customer@example.com", StoredSession.Unknown),
        )
    }

    @Test
    fun `saved session is ignored once auth has settled`() {
        val other = AuthState.FullAccount(userId = "u1", email = "someone.else@example.com")
        assertEquals(
            LoginFileAccountAction.SignOutThenSignIn,
            loginFileAccountAction(other, "customer@example.com", StoredSession.WithEmail("customer@example.com")),
        )
        assertEquals(
            LoginFileAccountAction.SignInOnly,
            loginFileAccountAction(AuthState.SignedOut, "customer@example.com", StoredSession.Unknown),
        )
    }

    @Test
    fun `stored session maps found and email to the four cases`() {
        assertEquals(StoredSession.Absent, storedSession(found = false, email = null))
        assertEquals(StoredSession.Unknown, storedSession(found = true, email = null))
        assertEquals(StoredSession.Unknown, storedSession(found = true, email = "  "))
        assertEquals(
            StoredSession.WithEmail("customer@example.com"),
            storedSession(found = true, email = "customer@example.com"),
        )
    }

    @Test
    fun `log label names the case and never the email`() {
        val file = "customer@example.com"
        val labels = listOf(
            storedSessionLogLabel(StoredSession.Absent, file),
            storedSessionLogLabel(StoredSession.WithEmail(" Customer@Example.com"), file),
            storedSessionLogLabel(StoredSession.WithEmail("someone.else@example.com"), file),
            storedSessionLogLabel(StoredSession.Unknown, file),
        )
        assertEquals(listOf("absent", "same", "other", "unknown"), labels)
    }
}
