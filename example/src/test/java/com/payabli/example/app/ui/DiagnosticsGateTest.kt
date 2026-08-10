package com.payabli.example.app.ui

import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoPaymentFlowController
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentOperation
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.StoredMethod
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning diagnostics off has to stop the recording, not just hide the panel.
 *
 * A flag that reaches the UI state alone leaves every request line recorded and held in memory
 * while the panel is hidden, and the screen looks the same either way.
 */
class DiagnosticsGateTest {
    private fun storedMethodResult() =
        PaymentResult(
            code = "1",
            reason = "Success",
            storedMethod = StoredMethod("demo-method-0001", "saved", "Approved"),
        )

    private fun methodModel(
        store: DiagnosticsStore,
        enabled: Boolean,
    ) = PaymentMethodViewModel(
        flow = DemoPaymentFlowController(PaymentOperation.StoreMethod, stepDelayMillis = 0),
        diagnostics = store,
        diagnosticsEnabled = enabled,
    )

    private fun captureModel(
        store: DiagnosticsStore,
        enabled: Boolean,
    ) = CaptureViewModel(
        flow = DemoPaymentFlowController(PaymentOperation.Capture, stepDelayMillis = 0),
        diagnostics = store,
        diagnosticsEnabled = enabled,
    )

    @Test
    fun `payment method records nothing at all when diagnostics are off`() {
        val store = DiagnosticsStore()
        val model = methodModel(store, enabled = false)

        model.onCompleted(storedMethodResult())
        model.onError(PaymentError.Payabli("Declined"))

        assertEquals(emptyList<String>(), store.messages.value)
    }

    @Test
    fun `capture records nothing at all when diagnostics are off`() {
        val store = DiagnosticsStore()
        val model = captureModel(store, enabled = false)

        model.onCompleted(PaymentResult(code = "1", reason = "Approved"))
        model.onError(PaymentError.Payabli("Declined"))

        assertEquals(emptyList<String>(), store.messages.value)
    }

    @Test
    fun `payment method records both outcomes when diagnostics are on`() {
        // The other half: gating the wrong way would pass the tests above and record nothing ever.
        val store = DiagnosticsStore()
        val model = methodModel(store, enabled = true)

        model.onCompleted(storedMethodResult())
        model.onError(PaymentError.Payabli("Declined"))

        assertEquals(2, store.messages.value.size)
    }

    @Test
    fun `capture records both outcomes when diagnostics are on`() {
        val store = DiagnosticsStore()
        val model = captureModel(store, enabled = true)

        model.onCompleted(PaymentResult(code = "1", reason = "Approved"))
        model.onError(PaymentError.Payabli("Declined"))

        assertEquals(2, store.messages.value.size)
    }
}
