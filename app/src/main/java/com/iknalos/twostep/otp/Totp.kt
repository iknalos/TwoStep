package com.iknalos.twostep.otp

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class OtpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512");

    companion object {
        fun parse(name: String?): OtpAlgorithm? =
            if (name.isNullOrBlank()) SHA1 else entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * Time-based one-time passwords (RFC 6238) on top of HOTP (RFC 4226).
 * GitHub uses the defaults: SHA1, 6 digits, a new code every 30 seconds.
 */
object Totp {
    private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

    fun counterAt(epochSeconds: Long, period: Int): Long = Math.floorDiv(epochSeconds, period.toLong())

    fun hotp(key: ByteArray, counter: Long, digits: Int, algorithm: OtpAlgorithm): String {
        require(digits in 6..8) { "Codes must be 6 to 8 digits" }
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        // dynamic truncation: the last nibble picks which 4 bytes become the code
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % POW10[digits]).toString().padStart(digits, '0')
    }

    fun code(key: ByteArray, epochSeconds: Long, period: Int = 30, digits: Int = 6,
             algorithm: OtpAlgorithm = OtpAlgorithm.SHA1): String =
        hotp(key, counterAt(epochSeconds, period), digits, algorithm)

    /** Seconds until the code changes, with the fraction, for the countdown ring. */
    fun secondsLeft(epochMillis: Long, period: Int): Double {
        val periodMs = period * 1000L
        return (periodMs - Math.floorMod(epochMillis, periodMs)) / 1000.0
    }

    /** "123456" -> "123 456", "12345678" -> "1234 5678": easier to read off and type in. */
    fun grouped(code: String): String = when (code.length) {
        6 -> code.substring(0, 3) + " " + code.substring(3)
        7 -> code.substring(0, 3) + " " + code.substring(3)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }
}
