// KevBox upstream-sync note: KevBox-only test. Covers SentryUrlScrubber and the scheme+host-only
// breadcrumbs in SentryNetworkBreadcrumbInterceptor.
package com.nuvio.tv.core.diagnostics

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * AIOStreams keeps its config, Premiumize API key included, in the URL path. Network
 * breadcrumbs and error text sent to Sentry must keep scheme and host only.
 */
class SentryUrlScrubberTest {

    private val secretPath = "eyJhcGlLZXkiOiJwbS1zZWNyZXQtMTIzIn0"
    private val addonUrl =
        "https://streams.kevbox.dev/$secretPath/stream/movie/tt0111161.json?token=abc#frag"

    @Test
    fun `network breadcrumb keeps scheme and host only`() {
        val breadcrumb = SentryNetworkBreadcrumbInterceptor().buildBreadcrumb(
            url = addonUrl.toHttpUrl(),
            method = "GET",
            statusCode = 200,
            elapsedMs = 42,
            error = null
        )

        assertEquals("https://streams.kevbox.dev", breadcrumb.data["url"])
        assertEquals("streams.kevbox.dev", breadcrumb.data["host"])
        assertEquals("GET", breadcrumb.data["method"])
        assertEquals(200, breadcrumb.data["status_code"])
        assertEquals(42L, breadcrumb.data["elapsed_ms"])
        assertFalse(breadcrumb.data.containsKey("path"))
        assertFalse(breadcrumb.data.containsKey("http.query"))
        assertFalse(breadcrumb.data.containsKey("http.fragment"))
        assertNoSecret(breadcrumb.data.values.joinToString(" "))
    }

    @Test
    fun `network breadcrumb error message drops url path`() {
        val breadcrumb = SentryNetworkBreadcrumbInterceptor().buildBreadcrumb(
            url = addonUrl.toHttpUrl(),
            method = "GET",
            statusCode = null,
            elapsedMs = 7,
            error = IOException("unexpected end of stream on $addonUrl")
        )

        assertEquals(
            "unexpected end of stream on https://streams.kevbox.dev",
            breadcrumb.data["error_message"]
        )
        assertEquals(IOException::class.java.name, breadcrumb.data["error_type"])
    }

    @Test
    fun `scrubText cuts every url to scheme and host`() {
        assertEquals(
            "failed https://streams.kevbox.dev then http://192.168.1.5 and https://[::1] done",
            SentryUrlScrubber.scrubText(
                "failed $addonUrl then http://user:pass@192.168.1.5:7000/$secretPath/manifest.json " +
                    "and https://[::1]:8443/$secretPath done"
            )
        )
        assertEquals("no urls here", SentryUrlScrubber.scrubText("no urls here"))
    }

    @Test
    fun `origin brackets ipv6 hosts and drops port`() {
        assertEquals(
            "http://[::1]",
            SentryUrlScrubber.origin("http://[::1]:7000/$secretPath/manifest.json".toHttpUrl())
        )
    }

    @Test
    fun `scrub breadcrumb from any source drops url paths`() {
        val breadcrumb = Breadcrumb.http(addonUrl, "GET").apply {
            message = "loading $addonUrl"
        }

        SentryUrlScrubber.scrub(breadcrumb)

        assertEquals("https://streams.kevbox.dev", breadcrumb.data["url"])
        assertEquals("loading https://streams.kevbox.dev", breadcrumb.message)
        assertNoSecret(breadcrumb.data.values.joinToString(" "))
    }

    @Test
    fun `scrub event drops url paths from message and exceptions`() {
        val event = SentryEvent().apply {
            message = Message().apply {
                message = "stream failed for $addonUrl"
                formatted = "stream failed for $addonUrl"
            }
            exceptions = listOf(SentryException().apply { value = "HTTP 403 from $addonUrl" })
        }

        SentryUrlScrubber.scrub(event)

        assertEquals("stream failed for https://streams.kevbox.dev", event.message?.message)
        assertEquals("stream failed for https://streams.kevbox.dev", event.message?.formatted)
        assertEquals("HTTP 403 from https://streams.kevbox.dev", event.exceptions?.single()?.value)
    }

    private fun assertNoSecret(text: String) {
        assertFalse("secret leaked: $text", text.contains(secretPath))
        assertFalse("query leaked: $text", text.contains("token=abc"))
    }
}
