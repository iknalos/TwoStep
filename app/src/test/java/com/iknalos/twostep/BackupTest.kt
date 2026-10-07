package com.iknalos.twostep

import com.iknalos.twostep.data.Account
import com.iknalos.twostep.data.AccountCodec
import com.iknalos.twostep.data.BackupCrypto
import com.iknalos.twostep.otp.OtpAlgorithm
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BackupTest {

    private val accounts = listOf(
        Account("a1", "GitHub", "iknalos", "JBSWY3DPEHPK3PXP", addedAt = 1_700_000_000_000),
        Account("a2", "ACME Co", "john@x.com", "HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", OtpAlgorithm.SHA256, 8, 60, 1_700_000_100_000),
    )

    @Test fun codecRoundTrip() {
        assertEquals(accounts, AccountCodec.decode(AccountCodec.encode(accounts)))
    }

    @Test fun codecRefusesNewerFormat() {
        val text = JSONObject(AccountCodec.encode(accounts)).put("format", 99).toString()
        assertThrows(IllegalArgumentException::class.java) { AccountCodec.decode(text) }
    }

    @Test fun toStringNeverShowsTheKey() {
        assertFalse(accounts[0].toString().contains("JBSWY3DPEHPK3PXP"))
    }

    /** Checked against Python's hashlib.pbkdf2_hmac('sha256', b'correct horse', b'0123456789abcdef', 1000, 32). */
    @Test fun pbkdf2KnownAnswer() {
        val key = BackupCrypto.deriveKey("correct horse".toCharArray(), "0123456789abcdef".toByteArray(), 1000)
        assertEquals("70183c0f60ee9e0441f64efab334e17f97a17f2073f7dd5acba3d3f12af09383",
            key.encoded.joinToString("") { "%02x".format(it) })
    }

    @Test fun backupRoundTrip_withRealIterationCount() {
        val plain = AccountCodec.encode(accounts).toByteArray()
        val file = BackupCrypto.encrypt(plain, "hunter2hunter2".toCharArray())
        assertTrue(BackupCrypto.isBackup(file))
        assertFalse(file.contains("JBSWY3DPEHPK3PXP"))
        assertEquals(BackupCrypto.ITERATIONS, JSONObject(file).getInt("iterations"))
        assertArrayEquals(plain, BackupCrypto.decrypt(file, "hunter2hunter2".toCharArray()))
        assertEquals(accounts, AccountCodec.decode(String(BackupCrypto.decrypt(file, "hunter2hunter2".toCharArray()))))
    }

    @Test fun wrongPasswordIsReportedAsSuch() {
        val file = BackupCrypto.encrypt("x".toByteArray(), "right password".toCharArray(), iterations = 10_000)
        assertThrows(BackupCrypto.WrongPassword::class.java) { BackupCrypto.decrypt(file, "wrong password".toCharArray()) }
    }

    @Test fun tamperingIsDetected() {
        val file = BackupCrypto.encrypt("secret stuff".toByteArray(), "pw123456".toCharArray(), iterations = 10_000)
        val o = JSONObject(file)
        val data = Base64.getDecoder().decode(o.getString("data"))
        data[0] = (data[0].toInt() xor 1).toByte()
        val flipped = JSONObject(file).put("data", Base64.getEncoder().encodeToString(data)).toString()
        assertThrows(BackupCrypto.WrongPassword::class.java) { BackupCrypto.decrypt(flipped, "pw123456".toCharArray()) }
        // lowering the iteration count in the file breaks the authenticated data too
        val weaker = JSONObject(file).put("iterations", 20_000).toString()
        assertThrows(BackupCrypto.WrongPassword::class.java) { BackupCrypto.decrypt(weaker, "pw123456".toCharArray()) }
    }

    @Test fun notABackup() {
        assertFalse(BackupCrypto.isBackup("hello"))
        assertFalse(BackupCrypto.isBackup(AccountCodec.encode(accounts)))
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt("{}", "x".toCharArray()) }
    }
}
