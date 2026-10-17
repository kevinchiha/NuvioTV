package com.nuvio.tv.updater

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * An install screen to open, and the APK to fall back to (the plain install screen, without
 * the profile) if [screen] can't be opened. Null when [screen] already is the plain one.
 */
data class PendingInstall(val screen: Intent, val fallbackApkPath: String?)

/**
 * One install screen waiting to be shown: Android's "Install this update?" confirmation, or the
 * plain install screen. Nothing in the updater starts these activities itself: the result
 * arrives in a receiver, and the install runs after a background download, and Android 10+
 * silently drops activity starts while the app has no visible window. The update dialog's code
 * opens what waits here once the app is resumed.
 */
object PendingInstallScreen {

    private val slot = MutableStateFlow<PendingInstall?>(null)
    val pending: StateFlow<PendingInstall?> = slot.asStateFlow()

    fun post(install: PendingInstall) {
        slot.value = install
    }

    /** Returns the waiting screen and empties the slot, so it is shown once. */
    fun take(): PendingInstall? = slot.getAndUpdate { null }
}
