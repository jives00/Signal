package com.signal.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption at rest for provider credentials (PLAN §5.7): AES-GCM with a key derived from the
 * SIGNAL_SECRET_KEY env var, which never sits in the database it protects. Output is base64 of a
 * random 12-byte IV followed by the ciphertext and tag.
 */
class SecretCipher(secret: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    /** Throws when the key is wrong or the text was tampered with. */
    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}
