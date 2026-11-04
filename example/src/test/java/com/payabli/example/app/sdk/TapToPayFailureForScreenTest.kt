package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.FailureText
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TapToPayFailureForScreenTest {
    @Test
    fun `the operator reads the category's message, not the reason`() {
        val failure = PayabliGenericException(PayabliErrorType.CARD_DECLINED, "Do not honour, from the issuer")

        val shown = failure.forScreen()

        assertEquals("The payment was declined. Try another payment method.", shown.shown)
        assertFalse(shown.toString().contains("honour"))
        assertSame(failure, shown.cause)
        assertEquals("the reason stays out of the throwable", PayabliErrorType.CARD_DECLINED.message, shown.message)
    }

    @Test
    fun `each error code that lands the ineligible reason has its own line, never the catalog message`() {
        val lines =
            listOf(
                PayabliErrorType.DEVICE_OS_UNSUPPORTED,
                PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED,
                PayabliErrorType.READER_DEVICE_REFUSED,
                PayabliErrorType.DEVICE_IDENTITY_UNAVAILABLE,
            ).map { type ->
                val line = requireNotNull(PayabliGenericException(type, "a reason").ineligibleLine)
                assertNotEquals(type.message, line)
                line
            }

        assertEquals("one line per type, so the operator can tell them apart", 4, lines.toSet().size)
        assertEquals(
            "This device isn't set up for Tap to Pay. Contact Payabli support with the device ID.",
            PayabliGenericException(PayabliErrorType.READER_DEVICE_REFUSED, "a reason").ineligibleLine,
        )
    }

    @Test
    fun `an ineligible device's result line is its own line, not the category's`() {
        val shown = PayabliGenericException(PayabliErrorType.DEVICE_OS_UNSUPPORTED, "a reason").forScreen().shown

        assertEquals("This phone's Android version is too old for Tap to Pay.", shown)
    }

    @Test
    fun `each activation refusal has its own line, never the catalog message`() {
        val types =
            listOf(
                PayabliErrorType.ACTIVATION_CODE_MALFORMED,
                PayabliErrorType.ACTIVATION_CODE_INCORRECT,
                PayabliErrorType.ACTIVATION_CODE_EXPIRED,
                PayabliErrorType.ACTIVATION_ATTEMPTS_EXHAUSTED,
                PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED,
                PayabliErrorType.DEVICE_NOT_PENDING,
            )

        val lines =
            types.map { type ->
                val failure = PayabliGenericException(type, "a reason")
                val shown = failure.forScreen().shown
                assertNotEquals(type.message, shown)
                assertNotEquals(FailureText.terminal(failure.toPaymentError().kind), shown)
                shown
            }

        assertEquals("one line per code, so the operator can tell them apart", types.size, lines.toSet().size)
        assertEquals("Enter the six-digit activation code.", lines.first())
    }

    @Test
    fun `a failure's event line carries its code, before and after it is put in the screen's words`() {
        val refused = PayabliGenericException(PayabliErrorType.ACTIVATION_CODE_INCORRECT, "a reason")

        assertEquals("code=3024", failureEventDetail(refused))
        assertEquals("code=3024", failureEventDetail(refused.forScreen()))
    }

    @Test
    fun `an error that lands another reason has no ineligible line`() {
        assertNull(PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "the token expired").ineligibleLine)
    }
}
