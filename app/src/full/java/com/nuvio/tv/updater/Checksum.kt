package com.nuvio.tv.updater

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 verification for downloaded APKs (ported from kevbox-support `Checksum`).
 *
 * This is an INTEGRITY check (guards against a corrupted/truncated download), NOT authenticity:
 * the hash comes from the same host as the APK (over the app's trust-all OkHttpClient), so a
 * forged manifest can supply a matching hash. Authenticity is enforced at install time by
 * Android's signing-certificate match against the installed app (the release keystore).
 * See the spec's Security section.
 */
internal object Checksum {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedSha256: String): Boolean =
        sha256(file).equals(expectedSha256.trim(), ignoreCase = true)
}
