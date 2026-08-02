package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.LogFieldRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/** The structured half of redaction: the allowlist decides, not the call site. */
class LogFieldRendererTest {
    /**
     * Deny-by-default fails safe, and therefore silently: a diagnostic whose name is not allowlisted
     * emits `[REDACTED]` and nobody notices the record is useless. Four shipped call sites were doing
     * exactly that. Each name below belongs to a real record, so this fails if one is dropped from the
     * allowlist or a call site is renamed away from it.
     */
    @Test
    fun everyNameAShippedRecordDependsOnSurvivesTheAllowlist() {
        mapOf(
            "callTimeoutMs" to "30000",
            "totalTimeoutMs" to "60000",
            "keyAlias" to "com.payabli.sdk.core.storage.v1.abcdef",
            "securityLevel" to "strongbox",
            "timeoutMs" to "250",
            "route" to "/api/v2/tokens",
            "statusCode" to "503",
        ).forEach { (name, value) ->
            assertEquals(
                "$name is used by a shipped log record and must render, not redact",
                "$name=$value",
                LogFieldRenderer.render(listOf(LogField.safe(name, value))),
            )
        }
    }

    @Test
    fun normalizationCollapsesSeparatorsAndCase() {
        listOf("cardNumber", "card-number", "Card_Number", "card number", "CARDNUMBER").forEach { name ->
            val rendered = LogFieldRenderer.render(listOf(LogField.safe(name, LogFixtures.DIGITS_16)))
            assertEquals("$name=[REDACTED]", rendered)
            assertFalse(rendered.contains(LogFixtures.DIGITS_16))
        }
    }

    @Test
    fun normalizationCollapsesToTheStoredForm() {
        assertEquals("statuscode", LogFieldRenderer.normalize("StatusCode"))
        assertEquals("statuscode", LogFieldRenderer.normalize("Status_Code"))
        assertEquals("httpmethod", LogFieldRenderer.normalize("HTTP-Method"))
    }

    @Test
    fun normalizationIsLocaleIndependent() {
        // The deprecated toLowerCase() used the default locale, which maps `I` to `ı` under Turkish
        // and would stop `RotationIndex` matching its allowlist entry. lowercase() does not.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("rotationindex", LogFieldRenderer.normalize("RotationIndex"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun nullRedactedRendersAsNull() {
        assertEquals("token=[null]", LogFieldRenderer.render(listOf(LogField.redacted("token", null))))
        assertEquals("token=[REDACTED]", LogFieldRenderer.render(listOf(LogField.redacted("token", "x"))))
    }

    @Test
    fun nullSafeValueRendersAsNull() {
        assertEquals("sid=[null]", LogFieldRenderer.render(listOf(LogField.safe("sid", null as String?))))
    }

    @Test
    fun allowlistedValueIsStillScrubbed() {
        // Belt and braces: this catches safe("sid", pan) at a call site.
        assertEquals(
            "sid=[REDACTED]",
            LogFieldRenderer.render(listOf(LogField.safe("sid", LogFixtures.DIGITS_16))),
        )
    }

    @Test
    fun allowlistedValueWithWhitespaceIsQuoted() {
        assertEquals(
            "outcome=\"soft decline\"",
            LogFieldRenderer.render(listOf(LogField.safe("outcome", "soft decline"))),
        )
    }

    @Test
    fun primitiveAndEnumOverloadsRender() {
        val rendered =
            LogFieldRenderer.render(
                listOf(
                    LogField.safe("statusCode", 402),
                    LogField.safe("durationMs", 12L),
                    LogField.safe("retryable", true),
                    LogField.safe("state", LogLevel.WARN),
                ),
            )
        assertEquals("statusCode=402 durationMs=12 retryable=true state=WARN", rendered)
    }

    @Test
    fun emptyFieldListRendersEmpty() {
        assertEquals("", LogFieldRenderer.render(emptyList()))
    }
}
