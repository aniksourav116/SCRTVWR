package srv.scrtvwr.preferences

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * Encrypts short secrets, such as server passwords, with an AES key that stays inside the
 * Android Keystore. A copy of the preferences file, including a backup restored on another
 * device, cannot be decrypted without that key.
 *
 * Results are remembered in both directions, so reading or re-saving servers does not hit the
 * Keystore each time and an unchanged secret keeps the same stored value.
 */
internal object KeystoreSecrets {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "scrtvwr_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private val storedValues = ConcurrentHashMap<String, String>()
    private val secrets = ConcurrentHashMap<String, String>()

    /** Stored form of [secret], or null when the Keystore fails and it cannot be kept safely. */
    fun encrypt(secret: String): String? =
        storedValues[secret] ?: runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            "${Base64.encode(cipher.iv)}:${Base64.encode(encrypted)}"
        }.onFailure { Timber.w(it, "Unable to encrypt a secret") }
            .getOrNull()
            ?.also { remember(secret, it) }

    /**
     * The secret [encrypt] stored as [value], or null when this device's key cannot open it,
     * as after a restore to another device.
     */
    fun decrypt(value: String): String? =
        secrets[value] ?: runCatching {
            val iv = Base64.decode(value.substringBefore(':'))
            val encrypted = Base64.decode(value.substringAfter(':'))
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.onFailure { Timber.d(it, "Unable to decrypt a secret") }
            .getOrNull()
            ?.also { remember(it, value) }

    private fun remember(secret: String, storedValue: String) {
        storedValues[secret] = storedValue
        secrets[storedValue] = secret
    }

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
