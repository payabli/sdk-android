package com.payabli.sdk.payin.form

import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reported name of every field a form can draw.
 *
 * Derived from the enum rather than mapped case by case, so this is what stands in for the compiler: a
 * renamed constant changes what the far side is counting, silently, and a count that changes meaning is
 * worse than one that stops.
 */
class PayInFieldTelemetryNameTest {
    @Test
    fun everyFieldReportsTheNameTheFarSideCountsBy() {
        assertEquals(
            listOf(
                "cardholder_name",
                "card_number",
                "card_expiration",
                "card_security_code",
                "card_postal_code",
                "account_holder",
                "routing_number",
                "account_number",
                "account_type",
                "account_holder_type",
                "sec_code",
                "device_id",
                "method_description",
                "first_name",
                "last_name",
                "customer_number",
                "billing_email",
                "billing_postal_code",
                "amount",
                "service_fee",
            ),
            PayInField.entries.map { it.telemetryName },
        )
    }

    /** A name the catalog would refuse is one the far side never sees, whatever this file says. */
    @Test
    fun everyNameSurvivesTheGate() {
        PayInField.entries.forEach { field ->
            val properties = mapOf(TelemetryProperty.FIELD.key to field.telemetryName)

            assertEquals(
                field.name,
                properties,
                TelemetryCatalog.scrub(TelemetryEvents.FORM_VALIDATION_ERROR, properties),
            )
        }
    }

    /**
     * The word every refusal reports, spelled out.
     *
     * A literal rather than a distinctness check: these are dimensions at the far end, so a renamed one is a
     * new column beside the old one rather than an error, and distinct-and-nonblank stays green through any
     * spelling.
     */
    @Test
    fun everyRejectionReportsItsOwnReason() {
        val reasons =
            listOf(
                PayInFieldError.DigitsOnly,
                PayInFieldError.ShorterThan(4),
                PayInFieldError.LongerThan(4),
                PayInFieldError.TooManyCharacters(4),
                PayInFieldError.NotExactly(4),
                PayInFieldError.OutsideRange(1, 4),
                PayInFieldError.CardNumberNotValid,
                PayInFieldError.RoutingNumberNotValid,
                PayInFieldError.EmailNotValid,
                PayInFieldError.ExpiryIncomplete,
                PayInFieldError.ExpiryPast,
                PayInFieldError.NotAccepted,
            ).map { it.reason }

        assertEquals(
            listOf(
                "digitsOnly",
                "shorterThan",
                "longerThan",
                "tooManyCharacters",
                "notExactly",
                "outsideRange",
                "cardNumberNotValid",
                "routingNumberNotValid",
                "emailNotValid",
                "expiryIncomplete",
                "expiryPast",
                "notAccepted",
            ),
            reasons,
        )
    }
}
