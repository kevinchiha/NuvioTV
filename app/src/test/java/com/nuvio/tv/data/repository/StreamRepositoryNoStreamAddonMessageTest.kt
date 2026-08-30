package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KevBox TV: a member whose session has ended keeps the baked-in default addons, none of which
 * declares a `stream` resource, so pressing Play used to report the addon-level message
 * ("No installed addon supports streams for movie") no matter why there was no source. These
 * tests pin the three cases apart so the message names the real problem.
 */
class StreamRepositoryNoStreamAddonMessageTest {

    @Test
    fun `signed out member is told to sign in rather than shown the addon message`() = runBlocking {
        val repository = newRepository(MutableStateFlow(AuthState.SignedOut))

        val result = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).first { it is NetworkResult.Error } as NetworkResult.Error

        assertEquals(SIGNED_OUT, result.message)
    }

    @Test
    fun `signed in member with no stream source is told their setup is missing, not to sign in`() = runBlocking {
        val repository = newRepository(
            MutableStateFlow(AuthState.FullAccount(userId = "member-1", email = "member@example.com"))
        )

        val result = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).first { it is NetworkResult.Error } as NetworkResult.Error

        assertEquals(NO_SOURCE, result.message)
    }

    @Test
    fun `member is not told they are signed out while auth is still resolving`() = runBlocking {
        val repository = newRepository(MutableStateFlow(AuthState.Loading))

        val result = repository.getStreamsFromAllAddons(
            type = "movie",
            videoId = "tt1341338",
            season = null,
            episode = null
        ).first { it is NetworkResult.Error } as NetworkResult.Error

        assertEquals(NO_ADDON, result.message)
    }

    private fun newRepository(authState: MutableStateFlow<AuthState>): StreamRepositoryImpl {
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(metaOnlyAddon()))

        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(emptyList())
        every { pluginManager.pluginsEnabled } returns flowOf(false)
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())

        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }

        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers { firstArg<List<AddonStreams>>() }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers {
            firstArg<List<AddonStreams>>()
        }

        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.authState } returns authState

        return StreamRepositoryImpl(
            context = stringResolvingContext(),
            api = mockk<AddonApi>(relaxed = true),
            addonRepository = addonRepository,
            pluginManager = pluginManager,
            profileManager = profileManager,
            debridSettingsDataStore = debridSettingsDataStore,
            tmdbService = mockk<TmdbService>(relaxed = true),
            debridStreamPresentation = presentation,
            localDebridAvailabilityService = availability,
            authManager = authManager
        )
    }

    /** Returns a marker per string resource so a test asserts which message was chosen, not its wording. */
    private fun stringResolvingContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.error_stream_signed_out) } returns SIGNED_OUT
        every { context.getString(R.string.error_stream_no_source_configured) } returns NO_SOURCE
        every { context.getString(R.string.error_stream_no_supported_addon, any()) } returns NO_ADDON
        return context
    }

    /** No `stream` resource, so no addon is even eligible — the same shape as the baked-in defaults. */
    private fun metaOnlyAddon(): Addon = Addon(
        id = "meta-only",
        name = "Meta Only",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example",
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(
            AddonResource(
                name = "meta",
                types = listOf("movie"),
                idPrefixes = listOf("tt")
            )
        )
    )

    private companion object {
        const val SIGNED_OUT = "signed-out-message"
        const val NO_SOURCE = "no-source-message"
        const val NO_ADDON = "no-addon-message"
    }
}
