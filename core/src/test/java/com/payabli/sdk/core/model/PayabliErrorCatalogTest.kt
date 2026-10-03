package com.payabli.sdk.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog as published, row by row. Each value is a contract with telemetry and support tooling, so every one
 * is pinned rather than only their shape: a renumbered member, a moved category or reworded text fails here.
 */
class PayabliErrorCatalogTest {
    private data class Row(
        val wireName: String,
        val number: Int,
        val category: PayabliErrorCategory,
        val message: String,
    )

    private val published =
        listOf(
            Row("MISSING_TOKEN", 1001, PayabliErrorCategory.CREDENTIAL, "No access token is available."),
            Row("TOKEN_EXPIRED", 1002, PayabliErrorCategory.CREDENTIAL, "The access token expired or was rejected."),
            Row("TOKEN_MALFORMED", 1003, PayabliErrorCategory.CREDENTIAL, "The access token could not be read."),
            Row(
                "TOKEN_PROVIDER_FAILED",
                1004,
                PayabliErrorCategory.CREDENTIAL,
                "The token provider did not return a usable token.",
            ),
            Row("INVALID_SIGNATURE", 1005, PayabliErrorCategory.CREDENTIAL, "The request signature was rejected."),
            Row(
                "PERMISSION_DENIED",
                1006,
                PayabliErrorCategory.CONFIGURATION,
                "The credentials are not permitted to make this request.",
            ),
            Row("SESSION_BURNED", 1007, PayabliErrorCategory.CREDENTIAL, "The session can no longer be used."),
            Row("PAYMENT_DECLINED", 1008, PayabliErrorCategory.DECLINED, "The payment was declined."),
            Row(
                "SERVER_ERROR",
                1009,
                PayabliErrorCategory.OUTCOME_UNKNOWN,
                "The service could not process the request.",
            ),
            Row("RATE_LIMITED", 1010, PayabliErrorCategory.RETRY_LATER, "Too many requests. Try again later."),
            Row(
                "CONFLICT",
                1011,
                PayabliErrorCategory.OUTCOME_UNKNOWN,
                "The request conflicts with the state the service holds.",
            ),
            Row(
                "INVALID_CONFIGURATION",
                1012,
                PayabliErrorCategory.CONFIGURATION,
                "The SDK is not configured correctly.",
            ),
            Row("NETWORK_ERROR", 1013, PayabliErrorCategory.OUTCOME_UNKNOWN, "The service could not be reached."),
            Row("DECODING_ERROR", 1014, PayabliErrorCategory.OUTCOME_UNKNOWN, "The response could not be read."),
            Row("USER_CANCELLED", 1015, PayabliErrorCategory.OUTCOME_UNKNOWN, "The person cancelled."),
            Row("VALIDATION_ERROR", 1016, PayabliErrorCategory.INVALID_REQUEST, "The request was refused as invalid."),
            Row("UNKNOWN", 1017, PayabliErrorCategory.OUTCOME_UNKNOWN, "An unexpected error occurred."),
            Row(
                "SDK_INTERNAL_ERROR",
                1018,
                PayabliErrorCategory.INTERNAL,
                "The SDK failed before the request was sent.",
            ),
            Row(
                "DEVICE_KEY_UNAVAILABLE",
                3001,
                PayabliErrorCategory.RETRY_LATER,
                "The device's key facility could not confirm this device's key.",
            ),
            Row(
                "ATTESTATION_NOT_SUPPORTED",
                3002,
                PayabliErrorCategory.DEVICE,
                "This device does not support app attestation.",
            ),
            Row(
                "ATTESTATION_SERVICES_OUTDATED",
                3003,
                PayabliErrorCategory.CONFIGURATION,
                "This device's attestation services must be installed or updated.",
            ),
            Row(
                "DEVICE_PENDING_ACTIVATION",
                3004,
                PayabliErrorCategory.CONFIGURATION,
                "This device is waiting for its activation code.",
            ),
            Row("ATTESTATION_REQUIRED", 3005, PayabliErrorCategory.CREDENTIAL, "This device must be attested again."),
            Row("ATTESTATION_REFUSED", 3006, PayabliErrorCategory.DEVICE, "This device's attestation was refused."),
            Row(
                "ATTESTATION_UNAVAILABLE",
                3007,
                PayabliErrorCategory.RETRY_LATER,
                "Attestation is temporarily unavailable.",
            ),
            Row(
                "ATTESTATION_NOT_CONFIGURED",
                3008,
                PayabliErrorCategory.CONFIGURATION,
                "Attestation is not configured for this app or environment.",
            ),
            Row(
                "ENTRY_POINT_REFUSED",
                3009,
                PayabliErrorCategory.CONFIGURATION,
                "The entry point is not available for this request.",
            ),
            Row(
                "READER_CREDENTIALS_UNUSABLE",
                3010,
                PayabliErrorCategory.CONFIGURATION,
                "The card reader's configuration is incomplete.",
            ),
            Row(
                "DEVICE_OS_UNSUPPORTED",
                3011,
                PayabliErrorCategory.DEVICE,
                "This device's operating system version cannot take contactless payments.",
            ),
            Row(
                "DEVICE_HARDWARE_UNSUPPORTED",
                3012,
                PayabliErrorCategory.DEVICE,
                "This device cannot take contactless payments.",
            ),
            Row(
                "TERMS_NOT_ACCEPTED",
                3013,
                PayabliErrorCategory.CONFIGURATION,
                "The merchant has not accepted the Tap to Pay terms.",
            ),
            Row(
                "CARD_PRESENT_NOT_ENABLED",
                3014,
                PayabliErrorCategory.CONFIGURATION,
                "Card-present payments are not enabled for this paypoint.",
            ),
            Row("READER_DEVICE_REFUSED", 3015, PayabliErrorCategory.DEVICE, "The card reader refused this device."),
            Row("READER_UNAVAILABLE", 3016, PayabliErrorCategory.RETRY_LATER, "The card reader could not be started."),
            Row("READER_SESSION_EXPIRED", 3017, PayabliErrorCategory.RETRY_LATER, "The card reader session expired."),
            Row("TAP_NOT_COMPLETED", 3018, PayabliErrorCategory.OUTCOME_UNKNOWN, "The card read did not complete."),
            Row("PAYMENT_NOT_OPENED", 3019, PayabliErrorCategory.DECLINED, "The service did not open the payment."),
            Row("CARD_DECLINED", 3020, PayabliErrorCategory.DECLINED, "The card was declined."),
            Row(
                "PAYMENT_OUTCOME_UNKNOWN",
                3021,
                PayabliErrorCategory.OUTCOME_UNKNOWN,
                "The payment's outcome could not be confirmed.",
            ),
            Row("PAYMENT_NOT_CLOSED", 3022, PayabliErrorCategory.OUTCOME_UNKNOWN, "The payment could not be closed."),
            Row(
                "ACTIVATION_CODE_MALFORMED",
                3023,
                PayabliErrorCategory.INVALID_REQUEST,
                "The activation code must be six digits.",
            ),
            Row(
                "ACTIVATION_CODE_INCORRECT",
                3024,
                PayabliErrorCategory.INVALID_REQUEST,
                "The activation code is incorrect.",
            ),
            Row(
                "ACTIVATION_CODE_EXPIRED",
                3025,
                PayabliErrorCategory.CONFIGURATION,
                "The activation code has expired.",
            ),
            Row(
                "ACTIVATION_ATTEMPTS_EXHAUSTED",
                3026,
                PayabliErrorCategory.CONFIGURATION,
                "Too many incorrect activation codes were entered.",
            ),
            Row(
                "ACTIVATION_CODE_NOT_ISSUED",
                3027,
                PayabliErrorCategory.CONFIGURATION,
                "No activation code has been issued for this device.",
            ),
            Row(
                "DEVICE_NOT_PENDING",
                3028,
                PayabliErrorCategory.INVALID_REQUEST,
                "This device is not waiting for activation.",
            ),
            Row(
                "TERMINAL_NOT_READY",
                3029,
                PayabliErrorCategory.INVALID_REQUEST,
                "The terminal is not ready for this call.",
            ),
            Row(
                "TOO_MANY_OPEN_CHARGES",
                3030,
                PayabliErrorCategory.INVALID_REQUEST,
                "Too many charges are waiting to be resolved.",
            ),
            Row(
                "PAYMENT_NOT_HELD",
                3031,
                PayabliErrorCategory.INVALID_REQUEST,
                "No captured payment is held under that identifier.",
            ),
        )

    @Test
    fun `every member matches its published row, in order`() {
        assertEquals(
            published,
            PayabliErrorType.entries.map { Row(it.wireName, it.code, it.category, it.message) },
        )
    }

    @Test
    fun `a member's wire name is its constant's name`() {
        PayabliErrorType.entries.forEach { assertEquals(it.name, it.wireName) }
    }

    @Test
    fun `numbers are unique and fall in their area's range`() {
        val numbers = PayabliErrorType.entries.map { it.code }
        assertEquals("a number is used twice", numbers.size, numbers.toSet().size)
        numbers.forEach {
            assertTrue(
                "$it is outside every area",
                it in 1001..1999 || it in 2001..2999 || it in 3001..3999,
            )
        }
    }

    @Test
    fun `numbers are appended within an area, so none is skipped`() {
        PayabliErrorType.entries.groupBy { it.code / 1000 }.values.forEach { area ->
            val numbers = area.map { it.code }
            assertEquals(
                ((numbers.first() / 1000) * 1000 + 1..(numbers.first() / 1000) * 1000 + numbers.size).toList(),
                numbers,
            )
        }
    }

    @Test
    fun `an error reads its entry's code, category and message directly`() {
        val failure = PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "Unauthorized", "the service's words")

        assertEquals(PayabliErrorType.TOKEN_EXPIRED, failure.type)
        assertEquals(1002, failure.code)
        assertEquals(PayabliErrorCategory.CREDENTIAL, failure.category)
        assertEquals("The access token expired or was rejected.", failure.message)
        assertEquals("PayabliGenericException(code=1002, type=TOKEN_EXPIRED)", failure.toString())
    }

    @Test
    fun `every category matches its published wire value, in order`() {
        assertEquals(
            listOf(
                "CREDENTIAL",
                "RETRY_LATER",
                "OUTCOME_UNKNOWN",
                "CONFIGURATION",
                "INVALID_REQUEST",
                "DECLINED",
                "DEVICE",
                "INTERNAL",
            ),
            PayabliErrorCategory.entries.map { it.wireName },
        )
    }

    @Test
    fun `a category's wire value is its constant's name`() {
        PayabliErrorCategory.entries.forEach { assertEquals(it.name, it.wireName) }
    }
}
