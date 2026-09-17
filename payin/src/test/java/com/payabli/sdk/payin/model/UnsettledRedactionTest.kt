package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.RedactedFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consumer half of the redaction contract. The producer is `:core`'s own `RedactedCause`, which carries
 * the marker this reads.
 *
 * A redaction happens once. `:core` and `:payin` each keep a three-line implementation and neither can see
 * the other's type, so what a carrier recognises is [RedactedFailure] and nothing narrower. These assert on
 * a redaction that is neither module's class, which is what makes the marker the thing under test rather
 * than the one implementation that happens to be reachable from here.
 */
class UnsettledRedactionTest {
    @Test
    fun `a cause that has already been redacted is the one carried`() {
        val already = ForeignRedaction()
        val failure =
            PayabliGenericException(
                PayabliErrorCode.SERVER_ERROR,
                "The service did not answer",
                cause = already,
            )

        assertSame(already, PayInException.Unsettled(failure).cause)
    }

    @Test
    fun `a cause that has not been redacted is redacted here, once`() {
        val failure = PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, "The read timed out")

        val carried = PayInException.Unsettled(failure).cause

        assertTrue("$carried", carried is RedactedFailure)
        assertTrue("${carried?.message}", carried?.message?.contains("PayabliGenericException") == true)
        assertFalse("${carried?.message}", carried?.message?.contains("The read timed out") == true)
    }

    /** Neither module's implementation, so only the marker can recognise it. */
    private class ForeignRedaction :
        Throwable("com.example.Whatever (message withheld)"),
        RedactedFailure
}
