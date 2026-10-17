package com.nuvio.tv.updater

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * One install screen waiting to be shown: Android's "Install this update?" confirmation, or the
 * plain install screen as a fallback. [InstallResultReceiver] posts here instead of starting the
 * activity itself, because Android 10+ silently drops activity starts from a receiver while the
 * app has no visible window. The update dialog's code shows it once the app is resumed.
 */
object PendingInstallScreen {

    private val slot = MutableStateFlow<Intent?>(null)
    val pending: StateFlow<Intent?> = slot.asStateFlow()

    fun post(intent: Intent) {
        slot.value = intent
    }

    /** Returns the waiting screen and empties the slot, so it is shown once. */
    fun take(): Intent? = slot.getAndUpdate { null }
}
