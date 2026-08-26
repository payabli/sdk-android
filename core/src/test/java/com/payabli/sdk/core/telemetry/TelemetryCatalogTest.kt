package com.payabli.sdk.core.telemetry

import org.junit.Assert.assertEquals
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
        val declaredKeys = constantsOf(TelemetryProperties::class.java, TelemetryProperties).toSet()

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
                    TelemetryProperties.OUTCOME to "approved",
                    "cardNumber" to "4111111111111111",
                    "amount" to "12.34",
                ),
            )

        assertEquals(mapOf(TelemetryProperties.OUTCOME to "approved"), scrubbed)
    }

    @Test
    fun aValueTooLongIsDroppedRatherThanTruncated() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(TelemetryProperties.OUTCOME to "a".repeat(TelemetryCatalog.MAX_VALUE_LENGTH + 1)),
            )

        assertEquals(emptyMap<String, String>(), scrubbed)
    }

    @Test
    fun anEmptyValueIsDropped() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(TelemetryProperties.OUTCOME to ""),
            )

        assertEquals(emptyMap<String, String>(), scrubbed)
    }

    @Test
    fun aValueOutsidePrintableAsciiIsDropped() {
        val scrubbed =
            TelemetryCatalog.scrub(
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                mapOf(
                    TelemetryProperties.OUTCOME to "approved",
                    // A non-breaking space, which reads as an ordinary one and is not ASCII.
                    TelemetryProperties.CODE to "declined\u00A0",
                ),
            )

        assertEquals(mapOf(TelemetryProperties.OUTCOME to "approved"), scrubbed)
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
}
