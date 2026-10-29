package com.nuvio.tv.updater

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets the non-injected [UpdateCheckWorker] (a plain CoroutineWorker) pull its singleton
 * dependencies from the app's Hilt graph via EntryPointAccessors.fromApplication(...).
 * This avoids @HiltWorker + Configuration.Provider wiring in the shared Application.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateWorkerEntryPoint {
    fun updateRepository(): UpdateRepository
    fun apkDownloader(): ApkDownloader
    fun updatePreferences(): UpdatePreferences
    // Silent install reads the signed-in account to match earlyUsers.
    fun authManager(): com.nuvio.tv.core.auth.AuthManager
}
