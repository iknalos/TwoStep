package com.iknalos.twostep.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The saved accounts, AES-256-GCM encrypted with a key that lives in the Android Keystore
 * (hardware-backed on most phones, and never readable by any app, this one included).
 *
 * Saving is the step GitHub's 28-day check exists for: some authenticator apps "failed to
 * save the TOTP secret after validating it". So every save writes atomically (a crash
 * mid-write leaves the old file intact), then reads the file back and decrypts it before
 * reporting success.
 */
class Vault(context: Context, fileName: String = "vault.bin", private val keyAlias: String = "twostep.vault") {

    private val dir = context.filesDir
    private val file = AtomicFile(File(dir, fileName))
    private val baseName = fileName

    sealed interface Loaded {
        data class Ok(val accounts: List<Account>) : Loaded
        object Empty : Loaded
        /** The file exists but can't be read. It is never overwritten automatically. */
        data class Broken(val reason: String) : Loaded
    }

    fun load(): Loaded {
        if (!file.baseFile.exists()) return Loaded.Empty
        return try {
            Loaded.Ok(AccountCodec.decode(String(decrypt(file.readFully()), Charsets.UTF_8)))
        } catch (e: Exception) {
            Loaded.Broken(e.message ?: e.javaClass.simpleName)
        }
    }

    @Throws(IOException::class)
    fun save(accounts: List<Account>) {
        val plain = AccountCodec.encode(accounts).toByteArray(Charsets.UTF_8)
        val bytes = encrypt(plain)
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)          // fsyncs, then renames over the old file
        } catch (e: Exception) {
            file.failWrite(out)
            throw IOException("Couldn't write the vault: ${e.message}", e)
        }
        val back = try { decrypt(file.readFully()) } catch (e: Exception) { null }
        if (back == null || !back.contentEquals(plain)) throw IOException("Saved file didn't read back correctly")
    }

    /** Moves an unreadable vault aside (kept, never deleted) so a restored backup can replace it. */
    fun quarantine(): File? {
        val f = file.baseFile
        if (!f.exists()) return null
        val dest = File(dir, "$baseName.unreadable-${System.currentTimeMillis()}")
        return if (f.renameTo(dest)) dest else null
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())        // the Keystore picks a fresh random IV
        cipher.updateAAD(MAGIC)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return MAGIC + byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        if (bytes.size < MAGIC.size + 1 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("Not a TwoStep vault file")
        }
        val ivLen = bytes[MAGIC.size].toInt()
        val ivStart = MAGIC.size + 1
        if (ivLen !in 12..16 || bytes.size < ivStart + ivLen + 16) throw IOException("Vault file is truncated")
        val key = existingKey() ?: throw IOException("This phone's TwoStep key is missing")
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, ivStart, ivLen))
        cipher.updateAAD(MAGIC)
        return cipher.doFinal(bytes, ivStart + ivLen, bytes.size - ivStart - ivLen)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? =
        (keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        // only ever created when there's no vault yet: a missing key with an existing file is "Broken"
        if (file.baseFile.exists()) throw IOException("This phone's TwoStep key is missing")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        val MAGIC = "TSV1".toByteArray(Charsets.US_ASCII)
    }
}
