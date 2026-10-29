package com.nuvio.tv.core.auth.loginfile

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a sign-in failure into the short English text the result file carries. KevBox Setup
 * shows it on the phone as-is, so it is fixed text: never the server reply or the password.
 */
fun loginFileErrorText(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(10).toList()
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    fun has(vararg parts: String) = parts.any { text.contains(it) }

    return when {
        has("invalid login credentials", "invalid_credentials") -> "Wrong email or password"
        has("email not confirmed") -> "This account's email is not confirmed yet"
        has("rate limit", "too many requests", "status=429") ->
            "Too many sign-in tries. Wait a minute and try again"
        chain.any { it is UnknownHostException || it is SocketTimeoutException || it is ConnectException } ||
            has("timeout", "unable to resolve host") ->
            "The TV could not reach the internet. Check its connection and try again"
        else -> "Sign-in did not work. Try again, or sign in on the TV"
    }
}
