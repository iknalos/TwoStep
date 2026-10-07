package com.iknalos.twostep

import com.iknalos.twostep.otp.OtpAlgorithm
import com.iknalos.twostep.otp.OtpAuthUri
import com.iknalos.twostep.otp.OtpParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpAuthUriTest {

    /** The shape of GitHub's QR code: label "GitHub:<username>", issuer GitHub, defaults otherwise. */
    @Test fun githubQrCode() {
        val p = OtpAuthUri.parse("otpauth://totp/GitHub:iknalos?secret=JBSWY3DPEHPK3PXP&issuer=GitHub")
        assertEquals(OtpParams("GitHub", "iknalos", "JBSWY3DPEHPK3PXP", OtpAlgorithm.SHA1, 6, 30), p)
    }

    @Test fun fullParameters() {
        val p = OtpAuthUri.parse("otpauth://totp/ACME%20Co:john.doe%40email.com?secret=hxdmvjecjjwsrb3hwizr4ifugftmxboz&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60")
        assertEquals("ACME Co", p.issuer)
        assertEquals("john.doe@email.com", p.name)
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", p.secret)
        assertEquals(OtpAlgorithm.SHA256, p.algorithm)
        assertEquals(8, p.digits)
        assertEquals(60, p.period)
    }

    @Test fun labelOnlyAndSpaces() {
        val p = OtpAuthUri.parse("otpauth://totp/Example:%20alice%2Btag@example.com?secret=JBSWY3DPEHPK3PXP")
        assertEquals("Example", p.issuer)
        assertEquals("alice+tag@example.com", p.name)
        val q = OtpAuthUri.parse("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP")
        assertEquals("", q.issuer)
        assertEquals("bob", q.name)
    }

    @Test fun plusInLabelIsLiteral_plusInQueryIsSpace() {
        val p = OtpAuthUri.parse("otpauth://totp/me+work@x.com?secret=JBSWY3DPEHPK3PXP&issuer=My+Site")
        assertEquals("me+work@x.com", p.name)
        assertEquals("My Site", p.issuer)
    }

    @Test fun caseInsensitiveSchemeAndAlgorithm() {
        val p = OtpAuthUri.parse("OTPAUTH://TOTP/x?SECRET=JBSWY3DPEHPK3PXP&Algorithm=sha512")
        assertEquals(OtpAlgorithm.SHA512, p.algorithm)
    }

    @Test fun roundTrip() {
        val p = OtpParams("ACME Co", "john@x.com", "JBSWY3DPEHPK3PXP", OtpAlgorithm.SHA512, 8, 60)
        assertEquals(p, OtpAuthUri.parse(OtpAuthUri.build(p)))
        val g = OtpParams("GitHub", "iknalos", "JBSWY3DPEHPK3PXP")
        assertEquals(g, OtpAuthUri.parse(OtpAuthUri.build(g)))
    }

    @Test fun refusesWhatItCantDo() {
        fun msg(s: String) = assertThrows(IllegalArgumentException::class.java) { OtpAuthUri.parse(s) }.message!!
        assertTrue(msg("https://github.com").contains("isn't a 2FA"))
        assertTrue(msg("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1").contains("HOTP"))
        assertTrue(msg("otpauth-migration://offline?data=abc").contains("Google Authenticator"))
        assertTrue(msg("otpauth://totp/x?issuer=Y").contains("no secret"))
        msg("otpauth://totp/x?secret=NOT-BASE32-189")
        msg("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=10")
        msg("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0")
        msg("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=MD5")
        msg("otpauth://totp/x%ZZ?secret=JBSWY3DPEHPK3PXP")
    }
}
