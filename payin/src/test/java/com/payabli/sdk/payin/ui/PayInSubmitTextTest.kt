package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.testOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The button names what the tap does, idle and while it runs. */
class PayInSubmitTextTest {
    private val strings: String = File("src/main/res/values/strings.xml").readText()

    @Test
    fun `each operation reads its own wording`() {
        assertEquals(PayInSubmitText.Capture, PayInSubmitText.of(PayabliPayInOperation.Capture(testOptions())))
        assertEquals(PayInSubmitText.Authorize, PayInSubmitText.of(PayabliPayInOperation.Authorize(testOptions())))
        assertEquals(PayInSubmitText.StoreMethod, PayInSubmitText.of(PayabliPayInOperation.StoreMethod()))
    }

    @Test
    fun `each wording names its resources`() {
        val resources = PayInSubmitText.entries.associateWith { it.idle to it.busy }

        assertEquals(
            mapOf(
                PayInSubmitText.Capture to
                    (R.string.payabli_payin_submit_capture to R.string.payabli_payin_busy_capture),
                PayInSubmitText.Authorize to
                    (R.string.payabli_payin_submit_authorize to R.string.payabli_payin_busy_authorize),
                PayInSubmitText.StoreMethod to
                    (R.string.payabli_payin_submit_store_method to R.string.payabli_payin_busy_store_method),
            ),
            resources,
        )
    }

    @Test
    fun `the default wording is the verb for the operation, in sentence case`() {
        assertEquals("Pay", declared("payabli_payin_submit_capture"))
        assertEquals("Paying…", declared("payabli_payin_busy_capture"))
        assertEquals("Authorize", declared("payabli_payin_submit_authorize"))
        assertEquals("Authorizing…", declared("payabli_payin_busy_authorize"))
        assertEquals("Save", declared("payabli_payin_submit_store_method"))
        assertEquals("Saving…", declared("payabli_payin_busy_store_method"))
    }

    private fun declared(name: String): String? =
        Regex("""<string name="$name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
}
