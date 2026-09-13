package app.aino.mobile.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.aino.mobile.core.network.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only AES-GCM ciphertext in private app preferences; the key never leaves Android Keystore. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun saveToken(token: String) {
        require(token.isNotBlank()) { "Token must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(IV, encode(cipher.iv))
            .putString(CIPHERTEXT, encode(encrypted))
            .apply()
    }

    @Synchronized
    override fun getToken(): String? {
        val encodedIv = preferences.getString(IV, null) ?: return null
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, decode(encodedIv)))
            }
            cipher.doFinal(decode(encodedCiphertext)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // Corrupt, restored, or invalidated ciphertext must not leave a partially usable credential.
            clearToken()
            null
        }
    }

    @Synchronized
    override fun clearToken() {
        preferences.edit().remove(IV).remove(CIPHERTEXT).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "aino_auth_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES = "aino_secure_credentials"
        const val IV = "token_iv"
        const val CIPHERTEXT = "token_ciphertext"
    }
}
