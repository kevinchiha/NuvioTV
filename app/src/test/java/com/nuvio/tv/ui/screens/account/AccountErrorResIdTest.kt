package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountErrorResIdTest {

    @Test
    fun `wrong password reply maps to invalid credentials, not invalid sync code`() {
        // The message AuthManager's AuthHttpException carries for a wrong password.
        val reply = "Auth request failed endpoint=/auth/v1/token?grant_type=password status=400 " +
            """body={"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""
        assertEquals(R.string.account_error_invalid_credentials, accountErrorResId(reply.lowercase()))
    }

    @Test
    fun `invalid sync code still maps to the sync code string`() {
        val reply = """{"code":"P0001","details":null,"hint":null,"message":"Invalid sync code"}"""
        assertEquals(R.string.account_error_invalid_sync_code, accountErrorResId(reply.lowercase()))
    }

    @Test
    fun `other auth reply with invalid in it does not map to the sync code string`() {
        val reply = "Auth request failed endpoint=/auth/v1/signup status=400 " +
            """body={"code":400,"error_code":"email_address_invalid","msg":"Email address \"x\" is invalid"}"""
        assertNotEquals(R.string.account_error_invalid_sync_code, accountErrorResId(reply.lowercase()))
    }

    @Test
    fun `incorrect pin still wins`() {
        assertEquals(R.string.account_error_incorrect_pin, accountErrorResId("incorrect pin for sync code"))
    }
}
