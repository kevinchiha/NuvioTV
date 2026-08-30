package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>

    /**
     * KevBox FORK DIVERGENCE — upstream has no equivalent. Keep on merge.
     *
     * Installed addons, having waited up to [timeoutMs] for every enabled addon's manifest to
     * arrive. Use this instead of `getInstalledAddons().first()` anywhere the WHOLE set matters,
     * because that flow publishes a partial list (cached manifests only) before the complete one:
     * reading the first value right after a sign-in silently drops any addon still being fetched,
     * which for a member is their only stream source. See AddonRepositoryImpl for the mechanics.
     *
     * Returns whatever has resolved when the timeout expires, so an unreachable addon delays a
     * search but can never block it. The default is the old unwaiting read, which keeps test
     * fakes compiling — real behaviour lives in the implementation.
     */
    suspend fun awaitResolvedInstalledAddons(timeoutMs: Long): List<Addon> =
        getInstalledAddons().first()
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)

    /**
     * Apply a remote member-config addon set to the PRIMARY addon store (the one every
     * inheriting sub-profile reads). Mirrors the member's [orderedUrls] exactly (incl. disabled
     * rows) and applies [enabledByUrl] on/off flags. Empty remote lists are ignored to avoid
     * mirror-wiping the local set. KevBox 'full' flavor (member config); on 'playstore'/upstream
     * the feature flag is off and nothing calls this.
     */
    suspend fun applyRemoteAddonConfig(orderedUrls: List<String>, enabledByUrl: Map<String, Boolean>)

    /**
     * Reset the PRIMARY addon store back to the baked default addons (all enabled). Used on a
     * shared-TV account switch (Gap K) when the new member has zero remote rows, so member B does
     * not inherit member A's list.
     */
    suspend fun resetPrimaryAddonsToDefaults()
}
