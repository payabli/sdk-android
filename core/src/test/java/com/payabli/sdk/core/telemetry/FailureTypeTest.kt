package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.impl.RedactedCause
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException

/** The `failure_type` a report carries: the failing type's simple name, read through a redaction. */
class FailureTypeTest {
    private object Outer {
        class Inner : Exception("never reported")
    }

    @Test
    fun `a failure names its own simple name`() {
        assertEquals("SocketTimeoutException", failureTypeOf(SocketTimeoutException("never reported")))
    }

    @Test
    fun `a redaction, or a failure carrying one, names the type it stands in for`() {
        val redacted = RedactedCause(Outer.Inner())
        val wrapped =
            PayabliGenericException(PayabliErrorType.TOKEN_PROVIDER_FAILED, "token refresh failed", cause = redacted)

        assertEquals("Inner", failureTypeOf(redacted))
        assertEquals("Inner", failureTypeOf(wrapped))
    }

    @Test
    fun `a cause that is no redaction does not stand in for the failure`() {
        val wrapped =
            PayabliGenericException(
                PayabliErrorType.NETWORK_ERROR,
                "dropped",
                cause = SocketTimeoutException("never reported"),
            )

        assertEquals("PayabliGenericException", failureTypeOf(wrapped))
    }
}
