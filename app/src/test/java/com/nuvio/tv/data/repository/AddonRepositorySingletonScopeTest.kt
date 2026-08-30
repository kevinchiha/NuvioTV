package com.nuvio.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Singleton

/**
 * KevBox TV. `RepositoryModule` scopes the AddonRepository *interface* binding with `@Singleton`,
 * but three classes inject the concrete [AddonRepositoryImpl] instead — StartupSyncService,
 * SubtitleRepositoryImpl and AccountViewModel. Without a scope on the class itself, each of those
 * gets its OWN instance, and every instance:
 *
 *  - runs `loadManifestCacheFromDisk()` in its `init`,
 *  - keeps a private `manifestCache`,
 *  - starts `installedAddonsFlow` eagerly, which fetches every enabled addon's manifest.
 *
 * So one sign-in fires the same manifest request several times within milliseconds. Observed
 * 2026-08-30: the member's AIOStreams host answered the first request and returned
 * **HTTP 429 Too Many Requests** to the next five. The instance backing the stream search was one
 * of the losers, so it cached nothing and the member's only stream source vanished from the
 * search — no error, just a short list.
 *
 * Reflection is the only way to pin a DI scope from a unit test, so this asserts the annotation
 * directly. If it ever fails, the storm is back.
 */
class AddonRepositorySingletonScopeTest {

    @Test
    fun `impl is singleton scoped so direct injections cannot each build their own manifest cache`() {
        assertTrue(
            "AddonRepositoryImpl must be @Singleton: classes injecting the concrete type would " +
                "otherwise each get an instance, and the duplicate manifest fetches rate-limit " +
                "the member's own addon host (HTTP 429).",
            AddonRepositoryImpl::class.java.isAnnotationPresent(Singleton::class.java)
        )
    }
}
