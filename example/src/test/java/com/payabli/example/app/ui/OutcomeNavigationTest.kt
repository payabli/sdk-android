package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.payment.DemoPaymentFlowController
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentOperation
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.StoredMethod
import com.payabli.example.app.payment.Transaction
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
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

/** Nothing under test here reads the values; only which instrument they came from. */
private val cardEntry = PayInFormValues(PayInMethodType.Card, emptyMap())

/**
 * A completion without the payload its screen exists to show is a failure, and must not push an
 * outcome screen.
 *
 * Navigating unconditionally puts "Payment method saved" on screen while the card behind it carries
 * an error line, which reads as a success that did not happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutcomeNavigationTest {
    /**
     * `viewModelScope` dispatches on `Dispatchers.Main`, which does not exist on a host JVM, so
     * `submit(cardEntry)` would never run and the test would report the state it started in.
     */
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun methodModel() =
        PaymentMethodViewModel(
            tokenClient = unusedTokenClient(),
            flow = DemoPaymentFlowController(PaymentOperation.StoreMethod, stepDelayMillis = 0),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    private fun captureModel() =
        CaptureViewModel(
            tokenClient = unusedTokenClient(),
            flow = DemoPaymentFlowController(PaymentOperation.Capture, stepDelayMillis = 0),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
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
        // The signal the pushed destination reads. Absent, that screen announces "Payment method
        // saved" against a model that came back empty from process death.
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
        // Not navigating is only half of it: the card behind carries a success glyph and a code, so
        // one response reads as a captured payment here and as an error on the other screen.
        val model = captureModel()
        model.onCompleted(PaymentResult(code = "1", reason = "Approved"))
        assertTrue(
            model.uiState.value.resultText
                .startsWith("✗"),
        )
    }

    @Test
    fun `an error after a completion takes the signal back down`() {
        // The form's error callback is independent of submission, so a failure can arrive after a
        // completion and before the navigation effect consumes the signal. A signal left standing
        // pushes "Payment method saved" on top of the error just recorded.
        val model = methodModel()
        model.onCompleted(
            PaymentResult(code = "1", storedMethod = StoredMethod("m", "saved", "Approved")),
        )
        assertTrue(model.uiState.value.outcomeReady)

        model.onError(PaymentError.Payabli("Declined"))
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `an error after a completion also clears what the pushed screen reads`() {
        val model = methodModel()
        model.onCompleted(
            PaymentResult(code = "1", storedMethod = StoredMethod("m", "saved", "Approved")),
        )
        model.onError(PaymentError.Payabli("Declined"))
        assertNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `an error after a capture clears the transaction it would have shown`() {
        val model = captureModel()
        model.onCompleted(
            PaymentResult(
                code = "1",
                transaction =
                    Transaction("txn", null, null, "card", "capture", "Captured", "1.00", "0.10", "demo"),
            ),
        )
        assertTrue(model.uiState.value.outcomeReady)

        model.onError(PaymentError.Payabli("Declined"))
        assertFalse(model.uiState.value.outcomeReady)
        assertNull("the previous payment is still there", model.uiState.value.lastResult)
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
            // Capture submits through its own controller. A fabricated stored-method result reaches
            // the transaction screen with no transaction, and leaves that controller uncalled.
            val model = captureModel()
            model.submit(cardEntry)
            val result = model.uiState.value.lastResult
            assertNotNull(result)
            assertNotNull("capture produced no transaction", result!!.transaction)
            assertNull(result.storedMethod)
            assertTrue(model.uiState.value.outcomeReady)
        }
}

/**
 * A token client pointed at a port nothing listens on.
 *
 * These tests never run the token check; the view models take the client so the first step of the
 * sequence has something to call. Port 1 refuses immediately rather than waiting for a timeout.
 */
private fun unusedTokenClient() = TokenServerClient(TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator))
