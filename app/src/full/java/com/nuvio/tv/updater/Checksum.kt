package com.nuvio.tv.updater

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 verification for downloaded APKs (ported from kevbox-support `Checksum`).
 * A compromised/misconfigured host cannot push an APK that doesn't match the manifest hash.
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
