package com.payabli.sdk.taptopay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the public result says about itself when something prints it.
 *
 * The card-not-present side asserts the same property in `PayInRedactionTest`, for the same reason: a
 * `toString` reaches assertion failures, exception messages and crash reports without passing through the
 * logger, so it is a second way out for anything the logging rule keeps in.
 */
class TapToPayResultTest {
    @Test
    fun `the rendered result names no transaction identifier`() {
        val rendered = TapToPayResult(paymentTransId = "tell-tale-trans-id", cardNetwork = "visa").toString()

        assertFalse(rendered, rendered.contains("tell-tale-trans-id"))
        assertTrue(rendered, rendered.contains("hasPaymentTransId=true"))
    }
}
