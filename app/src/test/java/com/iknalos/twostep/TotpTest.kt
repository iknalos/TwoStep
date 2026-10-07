package com.iknalos.twostep

import com.iknalos.twostep.otp.Base32
import com.iknalos.twostep.otp.OtpAlgorithm
import com.iknalos.twostep.otp.Totp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Expected values are the published tables in RFC 6238 Appendix B and RFC 4226 Appendix D. */
class TotpTest {

    private val sha1Seed = "12345678901234567890".toByteArray()
    private val sha256Seed = "12345678901234567890123456789012".toByteArray()
    private val sha512Seed = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    // time, SHA1, SHA256, SHA512 (8 digits, 30 s period)
    private val rfc6238 = listOf(
        arrayOf(59L, "94287082", "46119246", "90693936"),
        arrayOf(1111111109L, "07081804", "68084774", "25091201"),
        arrayOf(1111111111L, "14050471", "67062674", "99943326"),
        arrayOf(1234567890L, "89005924", "91819424", "93441116"),
        arrayOf(2000000000L, "69279037", "90698825", "38618901"),
        arrayOf(20000000000L, "65353130", "77737706", "47863826"),
    )

    @Test fun rfc6238_sha1() = rfc6238.forEach { r ->
        assertEquals("t=${r[0]}", r[1], Totp.code(sha1Seed, r[0] as Long, 30, 8, OtpAlgorithm.SHA1))
    }

    @Test fun rfc6238_sha256() = rfc6238.forEach { r ->
        assertEquals("t=${r[0]}", r[2], Totp.code(sha256Seed, r[0] as Long, 30, 8, OtpAlgorithm.SHA256))
    }

    @Test fun rfc6238_sha512() = rfc6238.forEach { r ->
        assertEquals("t=${r[0]}", r[3], Totp.code(sha512Seed, r[0] as Long, 30, 8, OtpAlgorithm.SHA512))
    }

    @Test fun rfc4226_hotp() {
        val want = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        want.forEachIndexed { counter, code -> assertEquals("counter $counter", code, Totp.hotp(sha1Seed, counter.toLong(), 6, OtpAlgorithm.SHA1)) }
    }

    /** GitHub's settings (SHA1, 6 digits, 30 s) are the last 6 digits of the SHA1 column. */
    @Test fun githubDefaults_fromBase32Key() {
        val key = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertArrayEquals(sha1Seed, key)
        assertEquals("287082", Totp.code(key, 59))
        assertEquals("081804", Totp.code(key, 1111111109))
        assertEquals("005924", Totp.code(key, 1234567890))
    }

    @Test fun sameCodeForTheWholePeriod() {
        val key = Base32.decode("JBSWY3DPEHPK3PXP")
        val start = 1_700_000_010L          // a multiple of 30
        val code = Totp.code(key, start)
        for (t in start until start + 30) assertEquals(code, Totp.code(key, t))
        assert(Totp.code(key, start + 30) != code || Totp.code(key, start + 60) != code)
    }

    @Test fun countdown() {
        assertEquals(30.0, Totp.secondsLeft(1_700_000_010_000L, 30), 1e-9)
        assertEquals(0.5, Totp.secondsLeft(1_700_000_039_500L, 30), 1e-9)
    }

    @Test fun grouping() {
        assertEquals("123 456", Totp.grouped("123456"))
        assertEquals("1234 5678", Totp.grouped("12345678"))
    }

    @Test fun rejectsBadDigitCounts() {
        assertThrows(IllegalArgumentException::class.java) { Totp.hotp(sha1Seed, 0, 5, OtpAlgorithm.SHA1) }
        assertThrows(IllegalArgumentException::class.java) { Totp.hotp(sha1Seed, 0, 9, OtpAlgorithm.SHA1) }
    }
}
