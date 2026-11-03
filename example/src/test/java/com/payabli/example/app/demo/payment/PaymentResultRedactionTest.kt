package com.payabli.example.app.demo.payment

import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.method.PaymentMethodUiState
import com.payabli.example.app.sdk.PayInForms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the three readout types say when stringified. Each is a `data class`, whose synthesized `toString` would
 * render every property into assertion failures and crash reports, which nothing redacts.
 */
class PaymentResultRedactionTest {
    @Test
    fun `a result names its code and not the method it stored`() {
        val result = PaymentResult(code = "A0003", storedMethod = StoredMethod("tok-77"))

        assertEquals("PaymentResult(code=A0003)", result.toString())
        assertFalse(result.toString().contains("tok-77"))
    }

    @Test
    fun `a stored method does not name its token`() {
        val stored = StoredMethod(storedMethodId = "tok-77")

        assertEquals("StoredMethod", stored.toString())
        assertFalse(stored.toString().contains("tok-77"))
    }

    /**
     * The stored-method screen's state, which quotes the identifier a later transaction charges.
     *
     * The panel's text is built as "Stored method: <id>", so a synthesized `toString` over this class would carry
     * the token that `StoredMethod` above declares its own `toString` to keep out.
     */
    @Test
    fun `the stored-method screen state names nothing it charges`() {
        val state =
            PaymentMethodUiState(
                setup = PayInForms.storePaymentMethod(),
                sampleIdentity = SampleIdentity.from("Test Device"),
                resultText = "✓ Stored method: tok-77",
                storedMethod = StoredMethod("tok-77"),
            )

        val rendered = state.toString()
        assertEquals("PaymentMethodUiState(finished=true)", rendered)
        assertFalse(rendered, rendered.contains("tok-77"))
    }

    @Test
    fun `a transaction says whether it has an identifier and not what it was worth`() {
        val transaction =
            Transaction(
                paymentTransactionId = "101-abc",
                gatewayTransactionId = "gtw-9",
                orderId = "order-1",
                method = "card",
                operation = "capture",
                status = "1",
                totalAmount = "10.00",
                feeAmount = "0.10",
                source = "fiserv",
            )

        assertEquals("Transaction(hasTransactionId=true)", transaction.toString())
        assertFalse(transaction.toString().contains("101-abc"))
        assertFalse(transaction.toString().contains("10.00"))
    }
}
