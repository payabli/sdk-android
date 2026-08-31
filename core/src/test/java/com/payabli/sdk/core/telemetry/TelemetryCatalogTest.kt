package com.payabli.sdk.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tests that keep the catalog reportable.
 *
 * Every one of these fails at build time for a mistake that would otherwise be invisible: an event whose
 * name is a shape nothing accepts, or a key nothing records, is dropped in silence at the far end and looks
 * exactly like an event that was never emitted.
 */
class TelemetryCatalogTest {
    /** Two to four dot-separated segments, a lowercase first letter, no underscores and no hyphens. */
    private val nameShape = Regex("^[a-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*){1,3}$")

    private val declaredNames: List<String> = constantsOf(TelemetryEvents::class.java, TelemetryEvents)

    @Test
    fun everyDeclaredNameIsReportable() {
        assertTrue("no names were found by reflection", declaredNames.size > 20)
        declaredNames.forEach {
            assertTrue("$it does not match the accepted shape", nameShape.matches(it))
            assertTrue("$it is ${it.length} characters", it.length <= MAX_NAME_LENGTH)
        }
    }

    @Test
    fun everyDeclaredNameHasARowExceptTheOneThatIsNeverEmitted() {
        val missing = declaredNames.filter { it !in TelemetryCatalog.events }
        assertEquals(listOf(TelemetryEvents.SDK_TELEMETRY_DISABLED), missing)
    }

    @Test
    fun theEventThatIsNeverEmittedCannotBeEmitted() {
        assertNull(TelemetryCatalog.scrub(TelemetryEvents.SDK_TELEMETRY_DISABLED, emptyMap()))
    }

    @Test
    fun anEventOutsideTheCatalogIsDroppedWhole() {
        assertNull(TelemetryCatalog.scrub("payin.capture.invented", mapOf("outcome" to "approved")))
    }

    @Test
    fun everyAllowedKeyIsOneOfTheDeclaredProperties() {
        val declaredKeys = TelemetryProperty.entries.map { it.key }.toSet()

        TelemetryCatalog.events.forEach { event ->
            TelemetryCatalog.allowedKeys(event).forEach { key ->
                assertTrue("$event declares $key, which is not a property key", key in declaredKeys)
                assertTrue("$key is ${key.length} characters", key.length <= TelemetryCatalog.MAX_KEY_LENGTH)
            }
        }
    }

    @Test
    fun aKeyTheEventDoesNotDeclareIsDropped() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(
                    TelemetryProperty.OUTCOME.key to "approved",
                    "cardNumber" to "4111111111111111",
                    "amount" to "12.34",
                ),
            )

        assertEquals(mapOf(TelemetryProperty.OUTCOME.key to "approved"), scrubbed)
    }

    @Test
    fun aValueTooLongIsDroppedRatherThanTruncated() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(TelemetryProperty.OUTCOME.key to "a".repeat(TelemetryCatalog.MAX_VALUE_LENGTH + 1)),
            )

        assertEquals(emptyMap<String, String>(), scrubbed)
    }

    @Test
    fun anEmptyValueIsDropped() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(TelemetryProperty.OUTCOME.key to ""),
            )

        assertEquals(emptyMap<String, String>(), scrubbed)
    }

    @Test
    fun aValueOutsidePrintableAsciiIsDropped() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(
                    TelemetryProperty.OUTCOME.key to "approved",
                    // A non-breaking space, which reads as an ordinary one and is not ASCII.
                    TelemetryProperty.CODE.key to "declined\u00A0",
                ),
            )

        assertEquals(mapOf(TelemetryProperty.OUTCOME.key to "approved"), scrubbed)
    }

    @Test
    fun noEventDeclaresMorePropertiesThanMayBeReported() {
        TelemetryCatalog.events.forEach {
            assertTrue(
                "$it declares ${TelemetryCatalog.allowedKeys(it).size} keys",
                TelemetryCatalog.allowedKeys(it).size <= TelemetryCatalog.MAX_PROPERTIES,
            )
        }
    }

    /** Every `String` constant a vocabulary object declares, so a new one joins these checks by existing. */
    private fun constantsOf(
        type: Class<*>,
        instance: Any,
    ): List<String> =
        type.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(instance) as String }

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
    // --- what leaves at once ---

    /**
     * The rule reads the outcome, so one event name is on both sides of it.
     *
     * This is the case a set of names cannot express: `payin.capture.completed` is the same event whether the
     * payment was taken or declined, and only one of those is worth interrupting a batch for.
     */
    @Test
    fun anOutcomeCarryingEventIsJudgedByItsOutcome() {
        val waits = TelemetryProperties.Outcome.SUCCESSFUL
        val leaves =
            setOf(
                TelemetryProperties.Outcome.DECLINED,
                TelemetryProperties.Outcome.REFUSED,
                TelemetryProperties.Outcome.FAILED,
                TelemetryProperties.Outcome.REFUSED_LOCALLY,
                TelemetryProperties.Outcome.INTERRUPTED,
            )

        waits.forEach { outcome ->
            assertFalse(
                outcome,
                TelemetryCatalog.forcesSend(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to outcome),
                ),
            )
        }
        leaves.forEach { outcome ->
            assertTrue(
                outcome,
                TelemetryCatalog.forcesSend(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to outcome),
                ),
            )
        }
    }

    /** Every outcome is on one side or the other, so a new one cannot be silently unclassified. */
    @Test
    fun everyOutcomeIsAccountedFor() {
        val all =
            setOf(
                TelemetryProperties.Outcome.SUCCEEDED,
                TelemetryProperties.Outcome.APPROVED,
                TelemetryProperties.Outcome.DECLINED,
                TelemetryProperties.Outcome.REFUSED,
                TelemetryProperties.Outcome.FAILED,
                TelemetryProperties.Outcome.REFUSED_LOCALLY,
                TelemetryProperties.Outcome.INTERRUPTED,
            )

        assertTrue(
            "an outcome exists that is neither successful nor a reason to send",
            all.containsAll(TelemetryProperties.Outcome.SUCCESSFUL),
        )
        all.forEach { outcome ->
            val forced =
                TelemetryCatalog.forcesSend(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to outcome),
                )
            assertEquals(outcome, outcome !in TelemetryProperties.Outcome.SUCCESSFUL, forced)
        }
    }

    /**
     * An unrecognised outcome forces a send.
     *
     * The safe direction, and the one a mistake should fall in: an outcome added and not classified is
     * reported early rather than held for a batch that may never leave.
     */
    @Test
    fun anOutcomeNobodyClassifiedIsSentRatherThanHeld() {
        assertTrue(
            TelemetryCatalog.forcesSend(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(TelemetryProperty.OUTCOME.key to "somethingNobodyNamedYet"),
            ),
        )
    }

    /** The events whose name is the whole story. They carry no outcome to read. */
    @Test
    fun anEventWhoseNameMeansFailureAlwaysLeavesAtOnce() {
        TelemetryCatalog.immediateEvents.forEach { event ->
            assertTrue(event, TelemetryCatalog.forcesSend(event, emptyMap()))
        }
    }

    /** And every one of them is an event this catalog will actually report. */
    @Test
    fun everyImmediateEventIsInTheCatalog() {
        assertTrue(
            TelemetryCatalog.immediateEvents.minus(TelemetryCatalog.events).toString(),
            TelemetryCatalog.events.containsAll(TelemetryCatalog.immediateEvents),
        )
    }

    /** A start, or a plain success, waits for its batch. Most of the stream is this. */
    @Test
    fun anEventThatReportsNothingWrongWaits() {
        listOf(
            TelemetryEvents.SDK_INITIALIZED to mapOf(TelemetryProperty.STATE.key to "ready"),
            TelemetryEvents.SDK_INITIALIZE_STARTED to mapOf(TelemetryProperty.STATE.key to "ready"),
            TelemetryEvents.AUTH_TOKEN_ACQUIRED to mapOf(TelemetryProperty.DURATION_MS.key to "12"),
            TelemetryEvents.AUTH_TOKEN_ACQUIRED to mapOf(TelemetryProperty.DURATION_MS.key to "8"),
        ).forEach { (event, properties) ->
            assertFalse(event, TelemetryCatalog.forcesSend(event, properties))
        }
    }
}
