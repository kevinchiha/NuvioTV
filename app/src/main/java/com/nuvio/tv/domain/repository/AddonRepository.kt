package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
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
