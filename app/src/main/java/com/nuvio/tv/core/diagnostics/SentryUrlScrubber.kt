// KevBox upstream-sync note: KevBox-only file. Addon URLs carry members' debrid API keys in
// their path (AIOStreams keeps its whole config there), so nothing sent to Sentry may keep more
// of a URL than its scheme and host. Used by SentryNetworkBreadcrumbInterceptor and the
// beforeBreadcrumb / beforeSend hooks in SentryInitializer.
package com.nuvio.tv.core.diagnostics

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import okhttp3.HttpUrl

internal object SentryUrlScrubber {
    // scheme, optional user:password@ (dropped), host or [ipv6] (kept), then port, path, query
    // and fragment (dropped).
    private val urlPattern = Regex(
        """\b(https?)://(?:[^/\s?#@]*@)?(\[[0-9a-fA-F:.]+\]|[^/\s?#@:"'<>]+)[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    fun origin(url: HttpUrl): String {
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return "${url.scheme}://$host"
    }

    fun scrubText(text: String): String {
        return urlPattern.replace(text) { match ->
            "${match.groupValues[1]}://${match.groupValues[2]}"
        }
    }

    fun scrub(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message?.let { breadcrumb.message = scrubText(it) }
        // Breadcrumb.http() stores the query and fragment outside "url".
        breadcrumb.removeData("http.query")
        breadcrumb.removeData("http.fragment")
        breadcrumb.data.entries.toList().forEach { (key, value) ->
            if (value is String) breadcrumb.setData(key, scrubText(value))
        }
        return breadcrumb
    }

    fun scrub(event: SentryEvent): SentryEvent {
        event.message?.let { message ->
            message.message?.let { message.message = scrubText(it) }
            message.formatted?.let { message.formatted = scrubText(it) }
        }
        event.exceptions?.forEach { exception ->
            exception.value?.let { exception.value = scrubText(it) }
        }
        return event
    }
}
