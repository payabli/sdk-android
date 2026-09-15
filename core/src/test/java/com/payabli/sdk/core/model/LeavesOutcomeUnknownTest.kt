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
        )

    /** Answered, so what comes next is a different request. */
    private val answered =
        setOf(
            PayabliErrorCode.MISSING_TOKEN,
            PayabliErrorCode.TOKEN_EXPIRED,
            PayabliErrorCode.TOKEN_MALFORMED,
            PayabliErrorCode.INVALID_SIGNATURE,
            PayabliErrorCode.PERMISSION_DENIED,
            PayabliErrorCode.SESSION_BURNED,
            PayabliErrorCode.PAYMENT_DECLINED,
            PayabliErrorCode.RATE_LIMITED,
            PayabliErrorCode.INVALID_CONFIGURATION,
            PayabliErrorCode.VALIDATION_ERROR,
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
