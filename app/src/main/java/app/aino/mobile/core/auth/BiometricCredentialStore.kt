package app.aino.mobile.core.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BiometricCredential(val credentialId: String, val deviceSecret: String)

/**
 * The server-issued device credential (`/auth/biometric/enroll`), encrypted
 * with an Android Keystore key that only unlocks after OS authentication.
 *
 * One enrollment serves both biometric sign-in and attendance clock-in/out.
 * Key v2 accepts a strong biometric (fingerprint / secure face unlock) OR the
 * device screen lock (PIN / pattern / password) — matching web/desktop, where
 * Windows Hello also accepts a PIN. The key is still invalidated when a new
 * biometric is enrolled on the device, forcing a re-enroll.
 *
 * On API 30+ the key is bound per-use (a CryptoObject is required). On API
 * 26–29 Android cannot bind DEVICE_CREDENTIAL to a CryptoObject, so the key is
 * time-bound: usable for [LEGACY_AUTH_WINDOW_SECONDS] after the OS prompt.
 */
class BiometricCredentialStore(context: Context, private val json: Json = Json) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun hasStoredCredential(): Boolean = preferences.contains(IV) && preferences.contains(CIPHERTEXT)

    /** A usable (key v2) credential is stored on this device. */
    fun isEnrolled(): Boolean = hasStoredCredential() && !needsUpgrade()

    /** Enrolled with the pre-v2 biometric-only key: the user must re-enable once. */
    fun needsUpgrade(): Boolean = hasStoredCredential() && preferences.getInt(KEY_VERSION, 1) < CURRENT_KEY_VERSION

    /** True when the key requires a CryptoObject-bound prompt (API 30+). */
    fun usesCryptoObject(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun createEncryptionCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }

    /**
     * Cipher for reading the stored credential, or null when none is usable
     * (nothing enrolled, pre-v2 enrollment, or the key was invalidated by a new
     * biometric enrollment — all of which wipe the local copy so the user can
     * re-enable cleanly).
     *
     * On API 26–29 call this only after a successful OS prompt; before it the
     * time-bound key throws [android.security.keystore.UserNotAuthenticatedException],
     * which is propagated so the caller can ask the user to authenticate again.
     */
    fun createDecryptionCipher(): Cipher? {
        if (needsUpgrade()) {
            clear()
            return null
        }
        val iv = preferences.getString(IV, null) ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, decode(iv)))
            }
        } catch (error: android.security.keystore.UserNotAuthenticatedException) {
            throw error
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(credential: BiometricCredential, authenticatedCipher: Cipher) {
        val ciphertext = authenticatedCipher.doFinal(json.encodeToString(credential).toByteArray())
        // The id is not secret (the device secret is); keeping it readable lets
        // the devices list recognise and revoke this device without a prompt.
        preferences.edit()
            .putString(IV, encode(authenticatedCipher.iv))
            .putString(CIPHERTEXT, encode(ciphertext))
            .putString(CREDENTIAL_ID, credential.credentialId)
            .putInt(KEY_VERSION, CURRENT_KEY_VERSION)
            .apply()
    }

    fun credentialId(): String? = preferences.getString(CREDENTIAL_ID, null)

    fun read(authenticatedCipher: Cipher): BiometricCredential {
        val ciphertext = preferences.getString(CIPHERTEXT, null) ?: error("No biometric credential is enrolled")
        return json.decodeFromString(authenticatedCipher.doFinal(decode(ciphertext)).toString(Charsets.UTF_8))
    }

    fun clear() {
        preferences.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.run {
                deleteEntry(KEY_ALIAS)
                deleteEntry(LEGACY_KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // A fresh v2 key: drop the biometric-only v1 key if one is left over.
        runCatching { keyStore.deleteEntry(LEGACY_KEY_ALIAS) }
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(LEGACY_AUTH_WINDOW_SECONDS)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun decode(value: String) = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aino_biometric_credential_v2"
        const val LEGACY_KEY_ALIAS = "aino_biometric_credential_v1"
        const val CURRENT_KEY_VERSION = 2
        const val KEY_VERSION = "key_version"
        const val LEGACY_AUTH_WINDOW_SECONDS = 10
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES = "aino_biometric_credential"
        const val IV = "iv"
        const val CIPHERTEXT = "ciphertext"
        const val CREDENTIAL_ID = "credential_id"
    }
}