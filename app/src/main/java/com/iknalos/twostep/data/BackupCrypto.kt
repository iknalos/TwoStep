package com.iknalos.twostep.data

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup files: PBKDF2-HMAC-SHA256 stretches the password into an
 * AES-256-GCM key. The phone's own vault key can't leave the phone, so this is the
 * only way accounts move to a new phone (or survive a lost one).
 */
object BackupCrypto {
    const val KIND = "encrypted-backup"
    const val ITERATIONS = 210_000
    private const val KDF = "PBKDF2WithHmacSHA256"

    class WrongPassword : Exception("Wrong password, or the file was changed")

    fun isBackup(text: String): Boolean =
        try { JSONObject(text).optString("kind") == KIND } catch (e: Exception) { false }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS,
                random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(iterations))
        val data = cipher.doFinal(plain)
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("app", "TwoStep")
            .put("kind", KIND)
            .put("version", 1)
            .put("kdf", KDF)
            .put("iterations", iterations)
            .put("salt", b64.encodeToString(salt))
            .put("cipher", "AES-256-GCM")
            .put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(data))
            .toString(2)
    }

    fun decrypt(fileText: String, password: CharArray): ByteArray {
        val o = try { JSONObject(fileText) } catch (e: Exception) { throw IllegalArgumentException("Not a TwoStep backup file") }
        require(o.optString("kind") == KIND) { "Not a TwoStep backup file" }
        require(o.optInt("version") == 1) { "This backup is from a newer TwoStep. Update the app first." }
        val iterations = o.optInt("iterations")
        require(iterations in 10_000..10_000_000) { "This backup file is damaged" }
        val b64 = Base64.getDecoder()
        val salt: ByteArray; val iv: ByteArray; val data: ByteArray
        try {
            salt = b64.decode(o.getString("salt"))
            iv = b64.decode(o.getString("iv"))
            data = b64.decode(o.getString("data"))
        } catch (e: Exception) {
            throw IllegalArgumentException("This backup file is damaged")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(iterations))
        return try { cipher.doFinal(data) } catch (e: AEADBadTagException) { throw WrongPassword() }
    }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    // binds the iteration count to the ciphertext, so editing it in the file can't weaken anything
    private fun aad(iterations: Int) = "TwoStep-backup-v1|$iterations".toByteArray(Charsets.UTF_8)
}
