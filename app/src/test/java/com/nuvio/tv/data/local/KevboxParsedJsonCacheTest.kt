package com.nuvio.tv.data.local

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
    fun `removed strings are dropped from the cache`() {
        cache.parseAll(setOf("a"))
        cache.parseAll(setOf("b"))
        parsed.clear()

        cache.parseAll(setOf("a"))

        assertEquals(listOf("a"), parsed)
    }
}
