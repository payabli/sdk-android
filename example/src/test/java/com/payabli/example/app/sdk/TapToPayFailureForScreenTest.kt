package com.payabli.example.app.sdk

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
