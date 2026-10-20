package com.payabli.example.app.sdk

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TapToPayFailureForScreenTest {
    @Test
    fun `the screen reads the reason and the catalog number, not the classification`() {
        val failure = PayabliGenericException(PayabliErrorType.CARD_DECLINED, "Do not honour, from the issuer")

        val shown = failure.forScreen()

        assertEquals("Do not honour, from the issuer (3020)", shown.shown)
        assertFalse(shown.toString().contains("honour"))
        assertSame(failure, shown.cause)
        assertEquals("the reason stays out of the throwable", PayabliErrorType.CARD_DECLINED.message, shown.message)
    }

    @Test
    fun `each error code that lands the ineligible reason carries its message`() {
        // Four error codes land that one reason and no fixed line can say which, so the message is
        // carried beside the reason: it says which of the four landed.
        listOf(
            PayabliErrorType.DEVICE_OS_UNSUPPORTED,
            PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED,
            PayabliErrorType.READER_DEVICE_REFUSED,
            PayabliErrorType.DEVICE_IDENTITY_UNAVAILABLE,
        ).forEach { type ->
            assertEquals(type.message, PayabliGenericException(type, "a reason").ineligibleMessage)
        }
    }

    @Test
    fun `an error that lands another reason carries no message`() {
        assertNull(PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "the token expired").ineligibleMessage)
    }
}
