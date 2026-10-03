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
            PayabliErrorCode.USER_CANCELLED,
            PayabliErrorCode.NETWORK_ERROR,
            PayabliErrorCode.SERVER_ERROR,
            PayabliErrorCode.DECODING_ERROR,
            PayabliErrorCode.UNKNOWN,
            PayabliErrorCode.TAP_NOT_COMPLETED,
            PayabliErrorCode.PAYMENT_OUTCOME_UNKNOWN,
            PayabliErrorCode.PAYMENT_NOT_CLOSED,
        )

    /** Answered, so what comes next is a different request. */
    private val answered =
        setOf(
            PayabliErrorCode.MISSING_TOKEN,
            PayabliErrorCode.TOKEN_EXPIRED,
            PayabliErrorCode.TOKEN_MALFORMED,
            PayabliErrorCode.TOKEN_PROVIDER_FAILED,
            PayabliErrorCode.INVALID_SIGNATURE,
            PayabliErrorCode.PERMISSION_DENIED,
            PayabliErrorCode.SESSION_BURNED,
            PayabliErrorCode.PAYMENT_DECLINED,
            PayabliErrorCode.RATE_LIMITED,
            PayabliErrorCode.CONFLICT,
            PayabliErrorCode.INVALID_CONFIGURATION,
            PayabliErrorCode.VALIDATION_ERROR,
            PayabliErrorCode.SDK_INTERNAL_ERROR,
            PayabliErrorCode.DEVICE_KEY_UNAVAILABLE,
            PayabliErrorCode.ATTESTATION_NOT_SUPPORTED,
            PayabliErrorCode.ATTESTATION_SERVICES_OUTDATED,
            PayabliErrorCode.DEVICE_PENDING_ACTIVATION,
            PayabliErrorCode.ATTESTATION_REQUIRED,
            PayabliErrorCode.ATTESTATION_REFUSED,
            PayabliErrorCode.ATTESTATION_UNAVAILABLE,
            PayabliErrorCode.ATTESTATION_NOT_CONFIGURED,
            PayabliErrorCode.ENTRY_POINT_REFUSED,
            PayabliErrorCode.READER_CREDENTIALS_UNUSABLE,
            PayabliErrorCode.DEVICE_OS_UNSUPPORTED,
            PayabliErrorCode.DEVICE_HARDWARE_UNSUPPORTED,
            PayabliErrorCode.TERMS_NOT_ACCEPTED,
            PayabliErrorCode.CARD_PRESENT_NOT_ENABLED,
            PayabliErrorCode.READER_DEVICE_REFUSED,
            PayabliErrorCode.READER_UNAVAILABLE,
            PayabliErrorCode.READER_SESSION_EXPIRED,
            PayabliErrorCode.PAYMENT_NOT_OPENED,
            PayabliErrorCode.CARD_DECLINED,
            PayabliErrorCode.ACTIVATION_CODE_MALFORMED,
            PayabliErrorCode.ACTIVATION_CODE_INCORRECT,
            PayabliErrorCode.ACTIVATION_CODE_EXPIRED,
            PayabliErrorCode.ACTIVATION_ATTEMPTS_EXHAUSTED,
            PayabliErrorCode.ACTIVATION_CODE_NOT_ISSUED,
            PayabliErrorCode.DEVICE_NOT_PENDING,
            PayabliErrorCode.TERMINAL_NOT_READY,
            PayabliErrorCode.TOO_MANY_OPEN_CHARGES,
            PayabliErrorCode.PAYMENT_NOT_HELD,
        )

    @Test
    fun `every member is classified, so a new one cannot arrive unclassified`() {
        assertEquals(
            "a member is in neither list, or in both",
            PayabliErrorCode.entries.toSet(),
            unknown + answered,
        )
        assertEquals("a member is in both lists", emptySet<PayabliErrorCode>(), unknown intersect answered)
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
