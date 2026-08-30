package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * KevBox TV. `getInstalledAddons()` is a StateFlow that publishes a PARTIAL list (only the addons
 * whose manifest is already cached) and the complete one a moment later. Reading it with `first()`
 * right after a sign-in therefore silently drops any addon still being fetched — which for a member
 * is their only stream source.
 *
 * Observed 2026-08-30 on the emulator: member config applied at 14:17:52, Play pressed at
 * 14:18:01.478 searched 2 addons, and the Kevbox addon's manifest landed at 14:18:01.653 — 175ms
 * too late. The member got a short stream list with no error and no way to tell why.
 */
class AddonRepositoryResolvedAddonsTest {

    @Test
    fun `resolved addons waits for a manifest that is still being fetched`() = runBlocking {
        val slowManifest = CompletableDeferred<Response<AddonManifestDto>>()
        val repository = newRepository(slowManifest)

        // Nothing can complete the slow manifest until this test does, so a plain
        // getInstalledAddons().first() here cannot contain SLOW_NAME — that is the read the
        // stream search used to do, and why it lost the member's only stream addon.
        val resolved = async { repository.awaitResolvedInstalledAddons(timeoutMs = 5_000) }
        slowManifest.complete(Response.success(manifestDto(SLOW_NAME)))

        assertEquals(
            listOf(FAST_NAME, SLOW_NAME),
            withTimeout(5_000) { resolved.await() }.map { it.name }
        )
    }

    @Test
    fun `resolved addons gives up at the timeout so a dead addon cannot block playback`() = runBlocking {
        // Never completed: the addon's manifest never arrives, as with a dead or unreachable host.
        val repository = newRepository(CompletableDeferred())

        val resolved = withTimeout(5_000) {
            repository.awaitResolvedInstalledAddons(timeoutMs = 200)
        }

        assertEquals(listOf(FAST_NAME), resolved.map { it.name })
    }

    private fun newRepository(slowManifest: CompletableDeferred<Response<AddonManifestDto>>): AddonRepositoryImpl {
        val api = mockk<AddonApi>()
        coEvery { api.getManifest("$FAST_URL/manifest.json") } returns
            Response.success(manifestDto(FAST_NAME))
        coEvery { api.getManifest("$SLOW_URL/manifest.json") } coAnswers { slowManifest.await() }

        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddonUrls } returns flowOf(listOf(FAST_URL, SLOW_URL))
        every { preferences.userSetNames } returns flowOf(emptyMap())
        every { preferences.addonEnabledStates } returns
            flowOf(mapOf(FAST_URL to true, SLOW_URL to true))

        return AddonRepositoryImpl(
            api = api,
            preferences = preferences,
            addonSyncService = mockk<AddonSyncService>(relaxed = true),
            authManager = mockk<AuthManager>(relaxed = true),
            context = mockk<Context>(relaxed = true)
        )
    }

    private fun manifestDto(name: String) = AddonManifestDto(
        id = name,
        name = name,
        version = "1.0.0",
        resources = listOf("stream"),
        types = listOf("movie"),
        idPrefixes = listOf("tt")
    )

    private companion object {
        const val FAST_URL = "https://fast.example"
        const val SLOW_URL = "https://slow.example"
        const val FAST_NAME = "Fast Addon"
        const val SLOW_NAME = "Slow Addon"
    }
}
