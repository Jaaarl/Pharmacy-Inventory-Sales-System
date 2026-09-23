package com.medtryx.app.auth

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordHash(
    val encodedHash: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
    val algorithm: String,
)

/** Password-specific hashing for PINs/passwords. Plaintext is never persisted. */
class PasswordHasher(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun hash(secret: CharArray): PasswordHash {
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        return PasswordHash(
            encodedHash = derive(secret, salt, ITERATIONS),
            salt = salt,
            iterations = ITERATIONS,
            algorithm = ALGORITHM,
        )
    }

    fun matches(secret: CharArray, stored: PasswordHash): Boolean {
        if (stored.algorithm != ALGORITHM || stored.iterations < MINIMUM_ITERATIONS) return false
        return constantTimeEquals(derive(secret, stored.salt, stored.iterations), stored.encodedHash)
    }

    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        PBEKeySpec(secret, salt, iterations, KEY_LENGTH_BITS).let { spec ->
            try {
                SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var result = 0
        left.indices.forEach { index -> result = result or (left[index].toInt() xor right[index].toInt()) }
        return result == 0
    }

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 310_000
        const val MINIMUM_ITERATIONS = 310_000
        const val SALT_BYTES = 16
        const val KEY_LENGTH_BITS = 256
    }
}
