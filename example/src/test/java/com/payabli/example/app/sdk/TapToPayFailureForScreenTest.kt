package com.payabli.example.app.sdk

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class TapToPayFailureForScreenTest {
    @Test
    fun `the screen reads the reason and the catalog number, not the classification`() {
        val failure = PayabliGenericException(PayabliErrorCode.CARD_DECLINED, "The card was declined.")

        val shown = failure.forScreen()

        assertEquals("The card was declined. (3020)", shown.shown)
        assertSame(failure, shown.cause)
        assertEquals("the reason stays out of the throwable", "CARD_DECLINED", shown.message)
        assertFalse(shown.toString().contains("declined."))
    }
}
