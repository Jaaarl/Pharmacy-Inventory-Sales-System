package com.medtryx.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface SensitiveDataProtector {
    fun protect(plainText: String): String
}

/** AES-GCM field encryption. There is deliberately no plaintext fallback if the device keystore fails. */
class AndroidKeystoreSensitiveDataProtector(
    private val alias: String = "medtryx.customer-fields.v1",
) : SensitiveDataProtector {
    override fun protect(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "enc:v1:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(encrypted)}"
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
