package com.payabli.example.app.ui

import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoPaymentFlowController
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentOperation
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.StoredMethod
import com.payabli.example.app.payment.Transaction
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A completion without the payload its screen exists to show is a failure, and must not push an
 * outcome screen.
 *
 * The callback navigated unconditionally, so a result carrying no stored method presented "Payment
 * method saved" while the view model had already written an error line to the card behind it. The
 * reader saw a success that had not happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutcomeNavigationTest {
    /**
     * `viewModelScope` dispatches on `Dispatchers.Main`, which does not exist on a host JVM, so
     * `submit()` would never run and the test would report the state it started in.
     */
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun methodModel() =
        PaymentMethodViewModel(
            flow = DemoPaymentFlowController(PaymentOperation.StoreMethod, stepDelayMillis = 0),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
        )

    private fun captureModel() =
        CaptureViewModel(
            flow = DemoPaymentFlowController(PaymentOperation.Capture, stepDelayMillis = 0),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
        )

    @Test
    fun `a stored method raises the signal`() {
        val model = methodModel()
        model.onCompleted(
            PaymentResult(code = "1", storedMethod = StoredMethod("m", "saved", "Approved")),
        )
        assertTrue(model.uiState.value.outcomeReady)
    }

    @Test
    fun `a completion with no stored method does not raise it`() {
        val model = methodModel()
        model.onCompleted(PaymentResult(code = "1", reason = "Success"))
        assertFalse(model.uiState.value.outcomeReady)
        assertTrue(
            model.uiState.value.resultText
                .startsWith("✗"),
        )
    }

    @Test
    fun `a completion with no transaction does not raise it on capture`() {
        val model = captureModel()
        model.onCompleted(PaymentResult(code = "1", reason = "Approved"))
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `what was stored is held, so the pushed screen can check it still has something to show`() {
        // The signal the pushed destination reads. Without it that screen announced "Payment method
        // saved" after process death, when the model it reads had come back empty.
        val model = methodModel()
        assertNull(model.uiState.value.storedMethod)

        model.onCompleted(
            PaymentResult(code = "1", storedMethod = StoredMethod("m", "saved", "Approved")),
        )
        assertNotNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `a completion carrying no stored method leaves nothing for the pushed screen to show`() {
        val model = methodModel()
        model.onCompleted(PaymentResult(code = "1", reason = "Success"))
        assertNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `capture reports a missing transaction as a failure, as the other screen does`() {
        // Not navigating was half of it. The card behind still opened with a success glyph and a
        // code, so the same response read as a captured payment here and as an error there.
        val model = captureModel()
        model.onCompleted(PaymentResult(code = "1", reason = "Approved"))
        assertTrue(
            model.uiState.value.resultText
                .startsWith("✗"),
        )
    }

    @Test
    fun `an error never raises it`() {
        val model = methodModel()
        model.onError(PaymentError.Payabli("Declined"))
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `consuming the signal clears it, so returning does not push again`() {
        val model = captureModel()
        model.onCompleted(
            PaymentResult(
                code = "1",
                transaction =
                    Transaction("txn", null, null, "card", "capture", "Captured", "1.00", "0.10", "demo"),
            ),
        )
        assertTrue(model.uiState.value.outcomeReady)
        model.outcomeShown()
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `capture submits through its controller and gets a transaction, not a stored method`() =
        runTest {
            // The defect this replaced: the button fabricated a stored-method result on both screens,
            // so Capture reached its transaction screen with no transaction, and the operation's own
            // controller was never called by anything outside a test.
            val model = captureModel()
            model.submit()
            val result = model.uiState.value.lastResult
            assertNotNull(result)
            assertNotNull("capture produced no transaction", result!!.transaction)
            assertNull(result.storedMethod)
            assertTrue(model.uiState.value.outcomeReady)
        }
}
