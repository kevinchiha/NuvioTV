package com.nuvio.tv.data.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

/** KevBox: pins the parse counting of KevboxParsedJsonCache directly, without DataStore timing. */
class KevboxParsedJsonCacheTest {

    private val parsed = mutableListOf<String>()
    private val cache = KevboxParsedJsonCache { json ->
        parsed += json
        if (json.startsWith("bad")) null else json.uppercase()
    }

    @Test
    fun `an unchanged set is not parsed again`() {
        cache.parseAll(setOf("a", "b"))
        cache.parseAll(setOf("a", "b"))

        assertEquals(listOf("a", "b"), parsed)
    }

    @Test
    fun `only new strings are parsed after a change`() {
        cache.parseAll(setOf("a", "b"))
        parsed.clear()

        val result = cache.parseAll(setOf("a", "c"))

        assertEquals(listOf("c"), parsed)
        assertEquals(listOf("A", "C"), result)
    }

    @Test
    fun `entries that fail to parse are skipped`() {
        assertEquals(listOf("A"), cache.parseAll(setOf("a", "bad")))
    }

    @Test
    fun `readers arriving together at a cold cache parse the list once`() {
        // App start: many screens subscribe to the watched list before the first parse finishes.
        val parses = AtomicInteger()
        val firstParseStarted = CountDownLatch(1)
        val slowCache = KevboxParsedJsonCache { json ->
            parses.incrementAndGet()
            firstParseStarted.countDown()
            Thread.sleep(50) // keep the first reader busy while the second arrives
            json
        }
        val raw = setOf("a", "b", "c")

        val first = thread { slowCache.parseAll(raw) }
        firstParseStarted.await(5, TimeUnit.SECONDS)
        val second = thread { slowCache.parseAll(raw) }
        first.join(5_000)
        second.join(5_000)

        assertEquals(raw.size, parses.get())
    }

    @Test
    fun `removed strings are dropped from the cache`() {
        cache.parseAll(setOf("a"))
        cache.parseAll(setOf("b"))
        parsed.clear()

        cache.parseAll(setOf("a"))

        assertEquals(listOf("a"), parsed)
    }
}
