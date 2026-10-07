package com.iknalos.twostep.otp

import java.io.ByteArrayOutputStream

/**
 * RFC 4648 base32, the encoding sites use for the "setup key" behind a 2FA QR code.
 * Decoding is forgiving about how people type keys: lower case, spaces, dashes and
 * '=' padding are all accepted.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Upper case with spaces, dashes and padding removed: the form a key is stored in. */
    fun normalize(input: String): String =
        input.uppercase().filter { !it.isWhitespace() && it != '-' && it != '=' }

    /** Throws [IllegalArgumentException] with a message fit to show the user. */
    fun decode(input: String): ByteArray {
        val clean = normalize(input)
        require(clean.isNotEmpty()) { "The setup key is empty" }
        val out = ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "\"$c\" can't be in a setup key (only A-Z and 2-7)" }
            // never holds more than 12 meaningful bits, so the mask only drops spent ones
            buffer = ((buffer shl 5) or v) and 0xFFFF
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        val bytes = out.toByteArray()
        require(bytes.isNotEmpty()) { "The setup key is too short" }
        return bytes
    }

    /** Unpadded, the way authenticator apps show keys. */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = ((buffer shl 8) or (b.toInt() and 0xFF)) and 0xFFFF
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
