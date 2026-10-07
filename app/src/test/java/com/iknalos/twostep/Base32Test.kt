package com.iknalos.twostep

import com.iknalos.twostep.otp.Base32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/** RFC 4648 section 10 test vectors, plus the ways people actually type setup keys. */
class Base32Test {

    private val vectors = listOf(
        "f" to "MY======", "fo" to "MZXQ====", "foo" to "MZXW6===", "foob" to "MZXW6YQ=",
        "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI======",
    )

    @Test fun decodesRfcVectors() = vectors.forEach { (plain, enc) ->
        assertArrayEquals(enc, plain.toByteArray(), Base32.decode(enc))
    }

    @Test fun encodesRfcVectorsUnpadded() = vectors.forEach { (plain, enc) ->
        assertEquals(enc.trimEnd('='), Base32.encode(plain.toByteArray()))
    }

    @Test fun forgivingInput() {
        val want = Base32.decode("JBSWY3DPEHPK3PXP")
        assertArrayEquals(want, Base32.decode("jbsw y3dp ehpk 3pxp"))
        assertArrayEquals(want, Base32.decode("JBSW-Y3DP-EHPK-3PXP"))
        assertArrayEquals(want, Base32.decode(" jbswy3dpehpk3pxp\n"))
    }

    @Test fun roundTripsRandomKeys() {
        val rnd = Random(42)
        repeat(200) {
            val bytes = rnd.nextBytes(rnd.nextInt(1, 65))
            assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
        }
    }

    @Test fun rejectsBadCharacters() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("JBSWY3DP1") }   // 1 isn't base32
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("JBSWY3DP0") }   // nor is 0
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("   ") }
    }
}
