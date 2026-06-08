package com.nuvio.tv.core.memberconfig

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// App-wide DataStore (single value), modelled on LastSignInDataStore. Unique name — must not
// collide with the other stores in this app (last_sign_in, app_onboarding, debug_settings, …).
private val Context.memberConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "member_config")

/**
 * Remembers the last `user_id` that [MemberConfigService] applied member-addon config for, so a
 * shared TV can detect an account switch (Gap K): when the signed-in member changes AND the new
 * member has zero `member_addon` rows, the primary addon store is reset to the baked defaults
 * instead of inheriting the previous member's list.
 */
@Singleton
class MemberConfigPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.memberConfigDataStore
    private val lastAppliedUserIdKey = stringPreferencesKey("last_applied_user_id")

    /** The userId last applied, or null if member config has never run on this device. */
    suspend fun lastAppliedUserId(): String? =
        dataStore.data.first()[lastAppliedUserIdKey]

    suspend fun setLastAppliedUserId(userId: String) {
        dataStore.edit { prefs -> prefs[lastAppliedUserIdKey] = userId }
    }
}
