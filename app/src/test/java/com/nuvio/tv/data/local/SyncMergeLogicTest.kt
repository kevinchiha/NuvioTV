package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncMergeLogicTest {
    private fun wi(id: String, s: Int?, e: Int?, at: Long) =
        WatchedItem(contentId = id, contentType = "series", title = id, season = s, episode = e, watchedAt = at)

    @Test fun `never-synced unions ALL local not in remote when opted in`() {
        val current = listOf(wi("a", 1, 1, 10), wi("b", 1, 2, 20))
        val remote = listOf(wi("a", 1, 1, 99)) // remote wins for overlap
        val (merged, preserved) = unionWatchedSnapshot(current, remote, lastSuccessfulPushMs = 0L, unionWhenNeverSynced = true)
        assertTrue(preserved)
        assertEquals(setOf("a" to 99L, "b" to 20L), merged.map { it.contentId to it.watchedAt }.toSet())
    }

    @Test fun `never-synced WITHOUT opt-in preserves nothing (today's pure replace, e_g_ TraktViewModel)`() {
        val current = listOf(wi("a", 1, 1, 10), wi("b", 1, 2, 20))
        val remote = listOf(wi("a", 1, 1, 99))
        val (merged, preserved) = unionWatchedSnapshot(current, remote, lastSuccessfulPushMs = 0L, unionWhenNeverSynced = false)
        assertFalse(preserved)
        assertEquals(setOf("a" to 99L), merged.map { it.contentId to it.watchedAt }.toSet())
    }

    @Test fun `synced keeps only local newer than last push (opt-in irrelevant)`() {
        val current = listOf(wi("b", 1, 2, 20), wi("c", 1, 3, 200))
        val remote = listOf(wi("a", 1, 1, 50))
        val (merged, preserved) = unionWatchedSnapshot(current, remote, lastSuccessfulPushMs = 100L, unionWhenNeverSynced = true)
        assertTrue(preserved)
        assertEquals(setOf("a", "c"), merged.map { it.contentId }.toSet())
    }

    @Test fun `remote-only when no local`() {
        val (merged, preserved) = unionWatchedSnapshot(emptyList(), listOf(wi("a", 1, 1, 1)), 0L, unionWhenNeverSynced = true)
        assertFalse(preserved)
        assertEquals(listOf("a"), merged.map { it.contentId })
    }

    private fun li(id: String, type: String) = SavedLibraryItem(
        id = id, type = type, name = id, poster = null,
        posterShape = PosterShape.POSTER, background = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), addonBaseUrl = null,
    )

    @Test fun `library union preserves local-only when preserveLocal`() {
        val current = listOf(li("x", "movie"), li("y", "series"))
        val remote = listOf(li("x", "MOVIE")) // case-insensitive type key
        val (merged, preserved) = unionLibrarySnapshot(current, remote, preserveLocal = true)
        assertTrue(preserved)
        assertEquals(setOf("x", "y"), merged.map { it.id }.toSet())
    }

    @Test fun `library replace (no preserve) drops local-only`() {
        val current = listOf(li("x", "movie"), li("y", "series"))
        val remote = listOf(li("x", "movie"))
        val (merged, preserved) = unionLibrarySnapshot(current, remote, preserveLocal = false)
        assertFalse(preserved)
        assertEquals(listOf("x"), merged.map { it.id })
    }
}
