package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.LogFieldRenderer
import com.payabli.sdk.core.logging.impl.LoggableFieldNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The structured half of redaction: the allowlist decides, not the call site. */
class LogFieldRendererTest {
    /**
     * Deny-by-default fails safe, and therefore silently: a diagnostic whose name is not allowlisted emits
     * `[REDACTED]`, and nobody notices the record is useless. Four shipped call sites were doing exactly that.
     *
     * So this reads the call sites rather than restating them. A hand-written list of names would drift the
     * moment one was added or renamed, and would have covered seven of the seventeen names in use. Scanning
     * catches both directions: a new field whose name nobody allowlisted, and a rename that walks a call site
     * off the list.
     *
     * It reads source text, so it only sees literal names, which is how every call site is written today. A
     * name assembled at runtime would pass unseen, and that is a reason not to assemble one.
     */
    @Test
    fun everyFieldNameAShippedRecordUsesIsAllowlisted() {
        val sources = File("src/main/java/com/payabli/sdk/core")
        assertTrue(
            "expected :core's sources at ${sources.absolutePath}, which is resolved from the module directory",
            sources.isDirectory,
        )

        val literalName = Regex("LogField\\.(?:safe|lastFour)\\(\\s*\"([^\"]+)\"")
        val callSites =
            sources
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file -> literalName.findAll(file.readText()).map { it.groupValues[1] to file.name } }
                .toList()

        // Without this the test passes vacuously when the scan finds nothing, which is the failure mode a
        // source-reading test has and an ordinary one does not.
        assertTrue(
            "the scan found ${callSites.size} field names, so it is not seeing the sources",
            callSites.size >= 15,
        )

        callSites.forEach { (name, file) ->
            assertTrue(
                "$file logs LogField.safe(\"$name\"); that name is not allowlisted, so the value renders [REDACTED]",
                LogFieldRenderer.normalize(name) in LoggableFieldNames.ALLOWED,
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
