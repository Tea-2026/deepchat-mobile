package com.deepseek.mobilechat.platform.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.deepseek.mobilechat.application.SecretStore
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AES-GCM [SecretStore] backed by a non-exportable Android Keystore key.
 *
 * - The key is generated once in `AndroidKeyStore` under [DEFAULT_KEY_ALIAS] and
 *   never leaves the keystore.
 * - Each value gets a fresh random 96-bit IV; `iv || ciphertext` is Base64-encoded
 *   into `SharedPreferences`. The Android Keystore refuses to reuse an IV for
 *   this transformation, so there is no nonce-reuse footgun here.
 * - [get] returns `null` for a missing entry *and* for anything that fails to
 *   decrypt (key invalidated, tampered/prefs migrated data). It never throws.
 * - No plaintext key material is ever logged; this class deliberately has no
 *   logging at all.
 */
class AndroidSecretStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override suspend fun put(ref: String, value: String) {
        withContext(Dispatchers.IO) {
            synchronized(LOCK) {
                val encrypted = encrypt(value)
                val committed = preferences.edit()
                    .putString(preferenceKey(ref), encrypted)
                    .commit()
                if (!committed) {
                    throw IOException("could not persist the encrypted secret")
                }
            }
        }
    }

    override suspend fun get(ref: String): String? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            try {
                val stored = preferences.getString(preferenceKey(ref), null)
                    ?: return@withContext null
                decrypt(stored)
            } catch (_: Exception) {
                // Invalidated keystore key, tampered/legacy payload, bad Base64,
                // or a corrupt preference entry: callers treat all of these as
                // "no secret", never as a crash.
                null
            }
        }
    }

    override suspend fun delete(ref: String) {
        withContext(Dispatchers.IO) {
            synchronized(LOCK) {
                preferences.edit().remove(preferenceKey(ref)).commit()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Crypto
    // -----------------------------------------------------------------------

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ciphertext, 0, blob, iv.size, ciphertext.size)
        return PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        require(stored.startsWith(PREFIX)) { "unsupported secret encoding" }
        val blob = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
        require(blob.size > IV_LENGTH_BYTES) { "encrypted secret is truncated" }
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun preferenceKey(ref: String): String = "secret:$ref"

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "mobilechat.secrets"

        private const val PREFERENCES_NAME = "mobilechat_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val LOCK = Any()
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "v1:"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
