package com.nuvio.tv.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts / decrypts a small secret (the family member's password) using an AES-256-GCM key held
 * in the Android Keystore — hardware-backed where the device supports it. The key never leaves the
 * Keystore; only the IV + ciphertext blob is persisted (in [LastSignInDataStore]). This replaces
 * the deprecated EncryptedSharedPreferences and powers the optional "one-tap re-login" on a
 * trusted family TV.
 *
 * Threat model: this protects the stored password from casual app-data scraping / backups, not
 * from an attacker with full device + unlock. That trade-off is deliberate for a shared TV that
 * has no per-user lock — the goal is to spare non-technical family members from re-typing a
 * password on an on-screen keyboard.
 */
object CredentialCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "kevbox_credential_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No setUserAuthenticationRequired(): a shared family TV has no per-user lock,
                // and requiring auth would defeat the one-tap convenience this exists for.
                .build()
        )
        return generator.generateKey()
    }

    /** Returns Base64(iv || ciphertext), or null if encryption fails. */
    fun encrypt(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }

    /** Inverse of [encrypt]; returns null on any failure (key rotated/cleared, corrupt blob). */
    fun decrypt(blob: String): String? = try {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        if (data.size <= IV_LENGTH) return null
        val iv = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    /** Deletes the Keystore key, rendering any previously-stored blob undecryptable. */
    fun clearKey() {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (t: Throwable) {
            // best-effort
        }
    }
}
