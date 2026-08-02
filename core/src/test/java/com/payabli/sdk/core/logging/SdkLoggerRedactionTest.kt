package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * The acceptance test: a log call carrying sensitive fields emits redacted output.
 *
 * `cardCvv` and `cardExp` are the load-bearing cases. They are three and four characters, so no
 * pattern can identify them; they are redacted solely because their names are not on the allowlist.
 * That is the concrete demonstration that deny-by-default, not pattern matching, protects short
 * secrets, and it is what a denylist design cannot claim.
 */
class SdkLoggerRedactionTest {
    private val sink = RecordingLogSink()
    private val logger: SdkLogger = DefaultSdkLogger(LogCategory.NETWORK, sink)

    @Test
    fun sensitiveFieldsAreRedactedAndAllowlistedFieldsAreNot() {
        logger.info(
            LogField.safe("cardNumber", LogFixtures.DIGITS_16),
            LogField.safe("cardCvv", LogFixtures.CVV),
            LogField.safe("achAccount", LogFixtures.ACH_ACCOUNT),
            LogField.safe("routingNumber", LogFixtures.ROUTING),
            LogField.safe("cardExp", LogFixtures.EXPIRY),
            LogField.safe("cardHolder", LogFixtures.CARDHOLDER),
            LogField.safe("billingEmail", LogFixtures.EMAIL),
            LogField.safe("sid", LogFixtures.SID),
            LogField.safe("route", LogFixtures.ROUTE),
            LogField.safe("statusCode", 402),
        ) { "capture rejected" }

        val record = sink.single()
        assertEquals(LogLevel.INFO, record.level)
        assertEquals("PayabliNetwork", record.tag)

        assertTrue(record.message.contains("sid=${LogFixtures.SID}"))
        assertTrue(record.message.contains("route=${LogFixtures.ROUTE}"))
        assertTrue(record.message.contains("statusCode=402"))
        assertTrue(record.message.contains("capture rejected"))

        listOf(
            "cardNumber",
            "cardCvv",
            "achAccount",
            "routingNumber",
            "cardExp",
            "cardHolder",
            "billingEmail",
        ).forEach { assertTrue("$it was not redacted", record.message.contains("$it=[REDACTED]")) }

        listOf(
            LogFixtures.DIGITS_16,
            LogFixtures.CVV,
            LogFixtures.ACH_ACCOUNT,
            LogFixtures.EXPIRY,
            LogFixtures.CARDHOLDER,
            LogFixtures.EMAIL,
        ).forEach { assertFalse("leaked $it", record.message.contains(it)) }
    }

    @Test
    fun geolocationIsNeverLoggable() {
        logger.info(
            LogField.safe("latitude", "12.345"),
            LogField.safe("longitude", "-6.789"),
        ) { "device located" }

        val message = sink.single().message
        assertTrue(message.contains("latitude=[REDACTED]"))
        assertTrue(message.contains("longitude=[REDACTED]"))
        assertFalse(message.contains("12.345"))
        assertFalse(message.contains("6.789"))
    }

    @Test
    fun interpolatedSecretInMessageIsScrubbed() {
        logger.info { "pan=${LogFixtures.DIGITS_16}" }

        val message = sink.single().message
        assertTrue(message.contains("pan=[REDACTED]"))
        assertFalse(message.contains(LogFixtures.DIGITS_16))
    }

    @Test
    fun typeTaggedTokenInMessageIsScrubbed() {
        logger.info { "refresh=${LogFixtures.REFRESH_TOKEN} access=${LogFixtures.ACCESS_JWT}" }

        val message = sink.single().message
        assertTrue(message.contains("refresh=[REDACTED]"))
        assertTrue(message.contains("access=[REDACTED]"))
        assertFalse(message.contains(LogFixtures.REFRESH_TOKEN))
        assertFalse(message.contains(LogFixtures.ACCESS_JWT))
    }

    @Test
    fun throwableMessageIsScrubbedButFramesSurvive() {
        val cause = NumberFormatException("For input string: \"${LogFixtures.DIGITS_16}\"")

        logger.error(cause, LogField.safe("route", LogFixtures.ROUTE)) { "decode failed" }

        val message = sink.single().message
        assertFalse(message.contains(LogFixtures.DIGITS_16))
        assertTrue(message.contains("java.lang.NumberFormatException"))
        assertTrue(message.contains("[REDACTED]"))
        assertTrue(message.contains("\n\tat "))
    }

    @Test
    fun unknownHostCauseStillProducesATrace() {
        // Log.getStackTraceString returns an empty string when any cause is an UnknownHostException,
        // silently discarding the most common transport failure. This pins that we do not use it.
        val cause = IOException("transport failed", UnknownHostException("api.payabli.invalid"))

        logger.error(cause) { "refresh failed" }

        val message = sink.single().message
        assertTrue(message.contains("java.io.IOException"))
        assertTrue(message.contains("java.net.UnknownHostException"))
        assertTrue(message.contains("\n\tat "))
    }

    @Test
    fun lastFourNeverExposesMoreThanFour() {
        logger.info(
            LogField.lastFour("sid", LogFixtures.SID),
            LogField.lastFour("shortRef", "abc"),
            LogField.lastFour("missingRef", null),
        ) { "correlated" }

        val message = sink.single().message
        assertTrue(message.contains("sid=[REDACTED]…b2c3"))
        assertFalse(message.contains(LogFixtures.SID))
        assertTrue(message.contains("shortRef=[REDACTED] "))
        assertTrue(message.endsWith("missingRef=[REDACTED]"))
    }

    @Test
    fun lastFourEmitsNoTailForAnUnlistedFieldName() {
        // The allowlist governs LastFour too, not just Safe. Without that check a call site could emit a
        // fragment of any field by choosing lastFour, which is the one decision the renderer exists to
        // take away from call sites.
        logger.info(LogField.lastFour("cardNumber", "abcdefgh")) { "correlated" }

        val message = sink.single().message
        assertTrue(message.contains("cardNumber=[REDACTED]"))
        assertFalse("no tail may follow an unlisted name", message.contains("efgh"))
        assertFalse("and no ellipsis, which would imply one", message.contains("…"))
    }

    @Test
    fun luhnValidityIsNotAccidental() {
        // A Luhn-valid digit run of PAN-plausible length would be a plausible PAN. Assert none of the
        // fixtures is, so a future edit to LogFixtures fails the build rather than quietly creating
        // one. CVV and EXPIRY are excluded: Luhn is meaningless at three and four digits.
        listOf(
            LogFixtures.DIGITS_11,
            LogFixtures.DIGITS_12,
            LogFixtures.DIGITS_16,
            LogFixtures.DIGITS_16_SPACED,
            LogFixtures.DIGITS_16_DASHED,
            LogFixtures.DIGITS_25,
            LogFixtures.EPOCH_MILLIS_13,
            LogFixtures.ACH_ACCOUNT,
            LogFixtures.ROUTING,
        ).forEach { assertFalse("fixture $it passes Luhn; pick another", isLuhnValid(it)) }
    }

    private fun isLuhnValid(value: String): Boolean {
        val sum =
            value
                .filter { it.isDigit() }
                .reversed()
                .mapIndexed { index, digit ->
                    val weighted = if (index % 2 == 1) digit.digitToInt() * 2 else digit.digitToInt()
                    if (weighted > 9) weighted - 9 else weighted
                }.sum()
        return sum % 10 == 0
    }
}
