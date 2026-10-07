package com.iknalos.twostep.data

import com.iknalos.twostep.otp.Base32
import com.iknalos.twostep.otp.OtpAlgorithm
import com.iknalos.twostep.otp.OtpParams
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One saved 2FA account. [secret] is normalized base32, exactly as the site issued it. */
data class Account(
    val id: String,
    val issuer: String,
    val name: String,
    val secret: String,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val addedAt: Long = 0,
) {
    val params get() = OtpParams(issuer, name, secret, algorithm, digits, period)

    /** Two entries with the same key and settings make the same codes: one is a duplicate. */
    fun sameKeyAs(other: Account) =
        secret == other.secret && algorithm == other.algorithm && digits == other.digits && period == other.period

    /** Hidden from toString so a stray log line can never print a key. */
    override fun toString() = "Account($id, $issuer, $name, $algorithm/$digits/$period)"

    companion object {
        fun from(p: OtpParams, now: Long = System.currentTimeMillis()) = Account(
            id = UUID.randomUUID().toString(),
            issuer = p.issuer.trim(),
            name = p.name.trim(),
            secret = Base32.normalize(p.secret),
            algorithm = p.algorithm,
            digits = p.digits,
            period = p.period,
            addedAt = now,
        )
    }
}

/**
 * The JSON both the encrypted vault and encrypted backups hold.
 * Never rename these fields: old backups must keep importing.
 */
object AccountCodec {
    const val FORMAT = 1

    fun encode(accounts: List<Account>): String {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(JSONObject()
                .put("id", a.id)
                .put("issuer", a.issuer)
                .put("name", a.name)
                .put("secret", a.secret)
                .put("algorithm", a.algorithm.name)
                .put("digits", a.digits)
                .put("period", a.period)
                .put("addedAt", a.addedAt))
        }
        return JSONObject().put("app", "TwoStep").put("format", FORMAT).put("accounts", arr).toString()
    }

    /** Throws [IllegalArgumentException] if anything in the file is unusable. */
    fun decode(text: String): List<Account> {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("Not a TwoStep accounts file") }
        val format = root.optInt("format", -1)
        require(format in 1..FORMAT) { "This file is from a newer TwoStep (format $format). Update the app first." }
        val arr = root.optJSONArray("accounts") ?: throw IllegalArgumentException("No accounts in this file")
        val out = ArrayList<Account>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val secret = Base32.normalize(o.optString("secret"))
            Base32.decode(secret)
            val algorithm = OtpAlgorithm.parse(o.optString("algorithm", "SHA1"))
                ?: throw IllegalArgumentException("Unsupported algorithm in account ${i + 1}")
            val digits = o.optInt("digits", 6)
            val period = o.optInt("period", 30)
            require(digits in 6..8 && period in 1..300) { "Account ${i + 1} has invalid code settings" }
            out += Account(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                issuer = o.optString("issuer"),
                name = o.optString("name"),
                secret = secret,
                algorithm = algorithm,
                digits = digits,
                period = period,
                addedAt = o.optLong("addedAt", 0),
            )
        }
        return out
    }
}
