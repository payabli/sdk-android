package com.payabli.sdk.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which failures leave it unknown whether a request was carried out.
 *
 * This decides whether a money-moving request keeps its idempotency key or takes a fresh one, so a member
 * classified the wrong way either refuses a payment that should go through or charges a payer twice. Both
 * card-not-present and card-present read it, which is why it lives here.
 *
 * **Every member is named, and the two lists are compared to the whole enum.** A member added later and
 * left out of both fails this rather than defaulting to "known", which is the direction that double-charges.
 */
class LeavesOutcomeUnknownTest {
    /** May have been carried out, so the attempt is kept. */
    private val unknown =
        setOf(
            PayabliErrorType.USER_CANCELLED,
            PayabliErrorType.NETWORK_ERROR,
            PayabliErrorType.SERVER_ERROR,
            PayabliErrorType.DECODING_ERROR,
            PayabliErrorType.UNKNOWN,
            PayabliErrorType.TAP_NOT_COMPLETED,
            PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN,
            PayabliErrorType.PAYMENT_NOT_CLOSED,
        )

    /** Answered, so what comes next is a different request. */
    private val answered =
        setOf(
            PayabliErrorType.MISSING_TOKEN,
            PayabliErrorType.TOKEN_EXPIRED,
            PayabliErrorType.TOKEN_MALFORMED,
            PayabliErrorType.TOKEN_PROVIDER_FAILED,
            PayabliErrorType.INVALID_SIGNATURE,
            PayabliErrorType.PERMISSION_DENIED,
            PayabliErrorType.SESSION_BURNED,
            PayabliErrorType.PAYMENT_DECLINED,
            PayabliErrorType.RATE_LIMITED,
            PayabliErrorType.CONFLICT,
            PayabliErrorType.INVALID_CONFIGURATION,
            PayabliErrorType.VALIDATION_ERROR,
            PayabliErrorType.SDK_INTERNAL_ERROR,
            PayabliErrorType.SESSION_NOT_INITIALIZED,
            PayabliErrorType.DEVICE_KEY_UNAVAILABLE,
            PayabliErrorType.DEVICE_SETUP_UNSUPPORTED,
            PayabliErrorType.DEVICE_SERVICES_OUTDATED,
            PayabliErrorType.DEVICE_PENDING_ACTIVATION,
            PayabliErrorType.DEVICE_SETUP_REQUIRED,
            PayabliErrorType.DEVICE_SETUP_REFUSED,
            PayabliErrorType.DEVICE_SETUP_UNAVAILABLE,
            PayabliErrorType.DEVICE_SETUP_NOT_CONFIGURED,
            PayabliErrorType.ENTRY_POINT_REFUSED,
            PayabliErrorType.READER_CREDENTIALS_UNUSABLE,
            PayabliErrorType.DEVICE_OS_UNSUPPORTED,
            PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED,
            PayabliErrorType.TERMS_NOT_ACCEPTED,
            PayabliErrorType.CARD_PRESENT_NOT_ENABLED,
            PayabliErrorType.READER_DEVICE_REFUSED,
            PayabliErrorType.READER_UNAVAILABLE,
            PayabliErrorType.READER_SESSION_EXPIRED,
            PayabliErrorType.PAYMENT_NOT_OPENED,
            PayabliErrorType.CARD_DECLINED,
            PayabliErrorType.ACTIVATION_CODE_MALFORMED,
            PayabliErrorType.ACTIVATION_CODE_INCORRECT,
            PayabliErrorType.ACTIVATION_CODE_EXPIRED,
            PayabliErrorType.ACTIVATION_ATTEMPTS_EXHAUSTED,
            PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED,
            PayabliErrorType.DEVICE_NOT_PENDING,
            PayabliErrorType.TERMINAL_NOT_READY,
            PayabliErrorType.TOO_MANY_OPEN_CHARGES,
            PayabliErrorType.PAYMENT_NOT_HELD,
            PayabliErrorType.DEVICE_IDENTITY_UNAVAILABLE,
        )

    @Test
    fun `every member is classified, so a new one cannot arrive unclassified`() {
        assertEquals(
            "a member is in neither list, or in both",
            PayabliErrorType.entries.toSet(),
            unknown + answered,
        )
        assertEquals("a member is in both lists", emptySet<PayabliErrorType>(), unknown intersect answered)
    }

    @Test
    fun `a failure that may have been carried out keeps the attempt`() {
        for (code in unknown) {
            assertEquals("$code should leave the outcome unknown", true, code.leavesOutcomeUnknown)
        }
    }

    @Test
    fun `a failure the service answered does not`() {
        for (code in answered) {
            assertEquals("$code is an answer, not an unknown", false, code.leavesOutcomeUnknown)
        }
    }
}
