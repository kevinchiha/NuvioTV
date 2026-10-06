package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.WatchedItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * KevBox: observeAllItems used to rebuild every WatchedItem from JSON on every DataStore emission,
 * once per reader. With a 3,000+ item history that was the top app hotspot on a TV.
 * See KevboxParsedJsonCache.kt.
 */
class KevboxWatchedItemsParseCacheTest {

    private val gson = Gson()
    private val watchedItemsKey = stringSetPreferencesKey("watched_items")

    @Test
    fun `items that did not change keep the same parsed object after an update`() = runTest {
        val preferences = harness(item("kept"))
        val before = preferences.observeAllItems(1).first()

        preferences.markAsWatched(item("added"), profileId = 1)
        val after = preferences.observeAllItems(1).first { it.size == 2 }

        assertSame(before.single { it.contentId == "kept" }, after.single { it.contentId == "kept" })
    }

    @Test
    fun `two readers share one parse`() = runTest {
        val preferences = harness(item("a"))

        val first = preferences.observeAllItems(1).first().single()
        val second = preferences.observeAllItems(1).first().single()

        assertSame(first, second)
    }

    @Test
    fun `getAllItems reuses the objects already parsed`() = runTest {
        // The delta sync calls getAllItems twice per run only to log a count.
        val preferences = harness(item("a"))

        val first = preferences.getAllItems(1).single()
        val second = preferences.getAllItems(1).single()
        val observed = preferences.observeAllItems(1).first().single()

        assertSame(first, second)
        assertSame(first, observed)
    }

    @Test
    fun `changing another key in the same store does not re-emit the watched list`() = runTest {
        val preferences = harness(item("a"))
        val emissions = Channel<List<WatchedItem>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            preferences.observeAllItems(1).collect { emissions.send(it) }
        }
        receiveInRealTime(emissions)

        preferences.setDeltaState(cursor = 42L, profileId = 1)
        // Give the collector real time to see the cursor-only change before the next write.
        withContext(Dispatchers.Default) { delay(300) }
        preferences.markAsWatched(item("b"), profileId = 1)

        val next = receiveInRealTime(emissions)
        collector.cancel()
        assertEquals(setOf("a", "b"), next.map { it.contentId }.toSet())
    }

    // runTest skips virtual time, so a plain withTimeout would fire before the Default-dispatcher collector runs.
    private suspend fun receiveInRealTime(channel: Channel<List<WatchedItem>>) =
        withContext(Dispatchers.Default) { withTimeout(5_000) { channel.receive() } }

    private fun item(contentId: String) = WatchedItem(
        contentId = contentId,
        contentType = "movie",
        title = contentId,
        watchedAt = 1L
    )

    private fun harness(vararg local: WatchedItem): WatchedItemsPreferences {
        val seeded = emptyPreferences().toMutablePreferences().apply {
            this[watchedItemsKey] = local.map { gson.toJson(it) }.toSet()
        }.toPreferences()
        val store = TestPreferencesDataStore(seeded)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns store
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return WatchedItemsPreferences(factory, profileManager)
    }

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            return mutex.withLock {
                transform(state.value).also { state.value = it }
            }
        }
    }
}
