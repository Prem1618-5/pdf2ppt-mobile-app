package app.papertoslides.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM (context §5.4). The AES key never leaves the Keystore; blobs are
 * [version 1][iv 12][ciphertext + tag]. Not androidx.security-crypto (deprecated).
 */
object KeyVault {
    private const val ALIAS = "papertoslides_vault_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // MVP: no biometrics / lock-screen coupling
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(true)); gen.generateKey()
        } catch (e: Exception) {
            // no StrongBox chip (or any StrongBox problem): fall back to the TEE-backed key
            gen.init(spec(false)); gen.generateKey()
        }
    }

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return byteArrayOf(1) + c.iv + c.doFinal(plain)
    }

    fun open(blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_BYTES && blob[0] == 1.toByte()) { "unknown vault format" }
        val iv = blob.copyOfRange(1, 1 + IV_BYTES)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return c.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }
}
