package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lastSignInDataStore: DataStore<Preferences> by preferencesDataStore(name = "last_sign_in")

/**
 * Remembers the last account that successfully signed in on this device. The email is stored in
 * plaintext so the form can prefill it; the password is stored only as an AES-GCM blob encrypted
 * by a key held in the Android Keystore (see [CredentialCrypto]) to power optional "one-tap
 * re-login" — a real convenience for a shared family TV where on-screen text entry is painful.
 */
@Singleton
class LastSignInDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.lastSignInDataStore
    private val lastEmailKey = stringPreferencesKey("last_email")
    private val encryptedPasswordKey = stringPreferencesKey("last_password_enc")

    /** Emits the last signed-in email, or null if none has been recorded. */
    val lastEmail: Flow<String?> = dataStore.data.map { prefs ->
        prefs[lastEmailKey]?.takeIf { it.isNotBlank() }
    }

    /** True when both an email and an encrypted password are stored → one-tap re-login is available. */
    val hasSavedCredential: Flow<Boolean> = dataStore.data.map { prefs ->
        !prefs[lastEmailKey].isNullOrBlank() && !prefs[encryptedPasswordKey].isNullOrBlank()
    }

    suspend fun setLastEmail(email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[lastEmailKey] = trimmed
        }
    }

    /**
     * Persists the email AND the encrypted password for one-tap re-login. If encryption is
     * unavailable for any reason, falls back to storing just the email (no plaintext password is
     * ever written).
     */
    suspend fun saveCredential(email: String, password: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) return
        val blob = if (password.isNotEmpty()) CredentialCrypto.encrypt(password) else null
        dataStore.edit { prefs ->
            prefs[lastEmailKey] = trimmed
            if (blob != null) prefs[encryptedPasswordKey] = blob else prefs.remove(encryptedPasswordKey)
        }
    }

    /** Decrypts and returns the stored password, or null if none is saved / decryption fails. */
    suspend fun decryptedPassword(): String? {
        val blob = dataStore.data.first()[encryptedPasswordKey]?.takeIf { it.isNotBlank() } ?: return null
        return CredentialCrypto.decrypt(blob)
    }

    /** Forgets the saved password but keeps the remembered email. */
    suspend fun clearCredential() {
        dataStore.edit { prefs -> prefs.remove(encryptedPasswordKey) }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(lastEmailKey)
            prefs.remove(encryptedPasswordKey)
        }
        CredentialCrypto.clearKey()
    }
}
