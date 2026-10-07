package com.iknalos.twostep

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.iknalos.twostep.data.Account
import com.iknalos.twostep.data.Vault
import com.iknalos.twostep.otp.OtpAlgorithm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a real Android (emulator in CI) with the real Keystore: the save path is the thing
 * GitHub's 28-day check guards against, so it gets tested where it actually runs.
 * Uses its own file and key alias so it never touches the app's real vault.
 */
@RunWith(AndroidJUnit4::class)
class VaultTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "vault-test.bin"
    private val alias = "twostep.vault.test"

    private val accounts = listOf(
        Account("a1", "GitHub", "iknalos", "JBSWY3DPEHPK3PXP", addedAt = 1),
        Account("a2", "ACME", "me@x.com", "HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", OtpAlgorithm.SHA512, 8, 60, 2),
    )

    @Before @After fun clean() {
        context.filesDir.listFiles()?.filter { it.name.startsWith(fileName) }?.forEach { it.delete() }
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun emptyAtFirst() {
        assertTrue(Vault(context, fileName, alias).load() is Vault.Loaded.Empty)
    }

    @Test fun savedAccountsSurviveANewInstance() {
        Vault(context, fileName, alias).save(accounts)
        // a fresh object = what happens after the app is killed, restarted or updated
        val loaded = Vault(context, fileName, alias).load()
        assertEquals(Vault.Loaded.Ok(accounts), loaded)
    }

    @Test fun fileOnDiskHoldsNoPlainKey() {
        Vault(context, fileName, alias).save(accounts)
        val bytes = File(context.filesDir, fileName).readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("JBSWY3DPEHPK3PXP"))
        assertFalse(text.contains("GitHub"))
    }

    @Test fun overwriteKeepsOnlyTheNewList() {
        val v = Vault(context, fileName, alias)
        v.save(accounts)
        v.save(accounts.take(1))
        assertEquals(Vault.Loaded.Ok(accounts.take(1)), Vault(context, fileName, alias).load())
    }

    @Test fun missingKeyIsBrokenNotEmpty_andIsNeverOverwritten() {
        Vault(context, fileName, alias).save(accounts)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        val v = Vault(context, fileName, alias)
        assertTrue(v.load() is Vault.Loaded.Broken)
        // saving must refuse rather than silently replace the unreadable file with a new key
        assertTrue(runCatching { v.save(accounts) }.isFailure)
        val moved = v.quarantine()
        assertTrue(moved != null && moved.exists())
        v.save(accounts)
        assertEquals(Vault.Loaded.Ok(accounts), Vault(context, fileName, alias).load())
    }

    @Test fun corruptFileIsBroken() {
        Vault(context, fileName, alias).save(accounts)
        val f = File(context.filesDir, fileName)
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
        f.writeBytes(bytes)
        assertTrue(Vault(context, fileName, alias).load() is Vault.Loaded.Broken)
    }
}
