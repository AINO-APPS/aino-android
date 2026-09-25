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

/** The device credential can only be encrypted/decrypted after OS authentication. */
class BiometricCredentialStore(context: Context, private val json: Json = Json) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnrolled(): Boolean = preferences.contains(IV) && preferences.contains(CIPHERTEXT)

    fun createEncryptionCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }

    fun createDecryptionCipher(): Cipher? {
        val iv = preferences.getString(IV, null) ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, decode(iv)))
            }
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
            .apply()
    }

    fun credentialId(): String? = preferences.getString(CREDENTIAL_ID, null)

    fun read(authenticatedCipher: Cipher): BiometricCredential {
        val ciphertext = preferences.getString(CIPHERTEXT, null) ?: error("No biometric credential is enrolled")
        return json.decodeFromString(authenticatedCipher.doFinal(decode(ciphertext)).toString(Charsets.UTF_8))
    }

    fun clear() {
        preferences.edit().clear().apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
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
        const val KEY_ALIAS = "aino_biometric_credential_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES = "aino_biometric_credential"
        const val IV = "iv"
        const val CIPHERTEXT = "ciphertext"
        const val CREDENTIAL_ID = "credential_id"
    }
}