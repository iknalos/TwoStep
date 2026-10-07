package com.iknalos.twostep.otp

import java.net.URLDecoder
import java.net.URLEncoder

/** What a site's 2FA QR code (or typed-in setup key) describes, before it's saved. */
data class OtpParams(
    val issuer: String,
    val name: String,
    val secret: String,                       // normalized base32
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
)

/**
 * Parses the Key URI format sites put in their QR codes, e.g. GitHub's
 * `otpauth://totp/GitHub:iknalos?secret=...&issuer=GitHub`.
 * Errors are [IllegalArgumentException]s with messages fit to show the user.
 */
object OtpAuthUri {

    fun parse(text: String): OtpParams {
        val s = text.trim()
        val schemeEnd = s.indexOf("://")
        val scheme = if (schemeEnd > 0) s.substring(0, schemeEnd).lowercase() else ""
        require(scheme != "otpauth-migration") {
            "That's a Google Authenticator export code. Add each account from its own site's QR code instead."
        }
        require(scheme == "otpauth") { "This QR code isn't a 2FA setup code" }

        val rest = s.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        require(slash > 0) { "This 2FA code is missing its account name" }
        val type = rest.substring(0, slash).lowercase()
        require(type != "hotp") { "This site uses counter-based codes (HOTP), which TwoStep doesn't support" }
        require(type == "totp") { "Unknown 2FA code type \"$type\"" }

        val afterType = rest.substring(slash + 1)
        val q = afterType.indexOf('?')
        val label = decodePath(if (q >= 0) afterType.substring(0, q) else afterType)
        val params = HashMap<String, String>()
        if (q >= 0) {
            for (pair in afterType.substring(q + 1).split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val k = decodeQuery(if (eq >= 0) pair.substring(0, eq) else pair).lowercase()
                val v = if (eq >= 0) decodeQuery(pair.substring(eq + 1)) else ""
                params.putIfAbsent(k, v)
            }
        }

        val rawSecret = params["secret"]
        require(!rawSecret.isNullOrBlank()) { "This 2FA code has no secret key in it" }
        val secret = Base32.normalize(rawSecret)
        Base32.decode(secret)   // throws if the key isn't valid base32

        // label is "Issuer:account" or just "account"; the issuer parameter wins when both exist
        val colon = label.indexOf(':')
        val labelIssuer = if (colon >= 0) label.substring(0, colon).trim() else ""
        val name = (if (colon >= 0) label.substring(colon + 1) else label).trim()
        val issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() } ?: labelIssuer

        val algorithm = OtpAlgorithm.parse(params["algorithm"])
            ?: throw IllegalArgumentException("Unsupported algorithm \"${params["algorithm"]}\"")
        val digits = params["digits"]?.trim()?.toIntOrNull() ?: if (params.containsKey("digits")) 0 else 6
        require(digits in 6..8) { "Codes must be 6 to 8 digits" }
        val period = params["period"]?.trim()?.toIntOrNull() ?: if (params.containsKey("period")) 0 else 30
        require(period in 1..300) { "The code period must be 1 to 300 seconds" }

        return OtpParams(issuer, name, secret, algorithm, digits, period)
    }

    /** The same format back again, e.g. for moving an account to another app. */
    fun build(p: OtpParams): String {
        val label = if (p.issuer.isNotEmpty()) "${encode(p.issuer)}:${encode(p.name)}" else encode(p.name)
        val sb = StringBuilder("otpauth://totp/").append(label).append("?secret=").append(p.secret)
        if (p.issuer.isNotEmpty()) sb.append("&issuer=").append(encode(p.issuer))
        if (p.algorithm != OtpAlgorithm.SHA1) sb.append("&algorithm=").append(p.algorithm.name)
        if (p.digits != 6) sb.append("&digits=").append(p.digits)
        if (p.period != 30) sb.append("&period=").append(p.period)
        return sb.toString()
    }

    // In the label '+' is a literal plus (user+tag@example.com); in the query it's a space.
    private fun decodePath(s: String) = decodeQuery(s.replace("+", "%2B"))

    private fun decodeQuery(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("This 2FA code is garbled (bad % escape)")
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
