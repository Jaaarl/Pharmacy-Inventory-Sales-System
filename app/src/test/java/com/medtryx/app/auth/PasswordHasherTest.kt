package com.medtryx.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test fun correct_secret_verifies_and_wrong_secret_is_rejected() {
        val stored = hasher.hash("1248".toCharArray())
        assertTrue(hasher.matches("1248".toCharArray(), stored))
        assertFalse(hasher.matches("9999".toCharArray(), stored))
    }

    @Test fun same_secret_receives_unique_salts() {
        val first = hasher.hash("1248".toCharArray())
        val second = hasher.hash("1248".toCharArray())
        assertFalse(first.salt.contentEquals(second.salt))
    }
}
