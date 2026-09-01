package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.demo.ui.method.PaymentMethodViewModel
import com.payabli.example.app.sdk.PayInForms
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.example.app.sdk.refusedOutcome
import com.payabli.example.app.sdk.storedMethodOutcome
import com.payabli.example.app.sdk.toOutcome
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.payment.PayInSubmissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A completion without the payload its screen exists to show is a failure, and must not push an outcome screen.
 *
 * Navigating unconditionally puts "Payment method saved" on screen while the card behind it carries an error
 * line, which reads as a success that did not happen.
 *
 * Every outcome here is one of the SDK's, handed to the callback the form calls, so these run through the
 * mapping in `PayInOutcomes` as well. What that mapping puts in each field is `PayInOutcomesTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutcomeNavigationTest {
    /**
     * `viewModelScope` dispatches on `Dispatchers.Main`, which does not exist on a host JVM, so constructing
     * either model would fail on the diagnostics collector it starts.
     */
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun methodModel() =
        PaymentMethodViewModel(
            setup = PayInForms.storePaymentMethod(),
            identity = SampleIdentity.from("Test Device"),
            startup = readyStartup(),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    private fun captureModel() =
        CaptureViewModel(
            identity = SampleIdentity.from("Test Device"),
            demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device")),
            startup = readyStartup(),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    @Test
    fun `a stored method raises the signal`() {
        val model = methodModel()
        model.onCompleted(storedMethodOutcome())
        assertTrue(model.uiState.value.outcomeReady)
    }

    @Test
    fun `a payment arriving at the stored-method screen does not raise it`() {
        // One flow's outcome reaching the other screen, which a shared session makes reachable: the payment
        // carries a transaction and no stored method, so this screen has nothing to describe.
        val model = methodModel()
        model.onCompleted(capturedPaymentOutcome())
        assertFalse(model.uiState.value.outcomeReady)
        assertTrue(
            model.uiState.value.resultText
                .startsWith("✗"),
        )
    }

    @Test
    fun `a stored method arriving at the capture screen does not raise it`() {
        val model = captureModel()
        model.onCompleted(storedMethodOutcome())
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `an approval carrying no transaction does not raise it on capture`() {
        // The service approved and returned no transaction record, which the SDK reports as it came.
        val model = captureModel()
        model.onCompleted(approvalWithoutTransaction())
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `what was stored is held, so the pushed screen can check it still has something to show`() {
        // The signal the pushed destination reads. Absent, that screen announces "Payment method saved"
        // against a model that came back empty from process death.
        val model = methodModel()
        assertNull(model.uiState.value.storedMethod)

        model.onCompleted(storedMethodOutcome())
        assertNotNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `a completion carrying no stored method leaves nothing for the pushed screen to show`() {
        val model = methodModel()
        model.onCompleted(capturedPaymentOutcome())
        assertNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `an approval carrying no transaction reads as an approval, not a failure`() {
        // An `A` code is a payment the service took. Reading it as a failure invites the payer to pay twice, and
        // the step list would offer the form again for a capture that had already gone through.
        val model = captureModel()

        model.onCompleted(approvalWithoutTransaction())

        assertTrue(
            model.uiState.value.resultText,
            model.uiState.value.resultText
                .startsWith("✓"),
        )
        assertFalse("the screen offered the form again", model.uiState.value.submitFailed)
        assertFalse("a screen describing a transaction was pushed", model.uiState.value.outcomeReady)
    }

    @Test
    fun `a failure after a completion takes the signal back down`() {
        // The form reports a refusal independently of a completion, so a failure can arrive after one and
        // before the navigation effect consumes the signal. A signal left standing pushes "Payment method
        // saved" on top of the error just recorded.
        val model = methodModel()
        model.onCompleted(storedMethodOutcome())
        assertTrue(model.uiState.value.outcomeReady)

        model.onFailed(refusedOutcome())
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `a failure after a completion also clears what the pushed screen reads`() {
        val model = methodModel()
        model.onCompleted(storedMethodOutcome())
        model.onFailed(refusedOutcome())
        assertNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `a failure after a capture clears the transaction it would have shown`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        assertTrue(model.uiState.value.outcomeReady)

        model.onFailed(refusedOutcome())
        assertFalse(model.uiState.value.outcomeReady)
        assertNull("the previous payment is still there", model.uiState.value.lastResult)
    }

    @Test
    fun `a failure never raises it`() {
        val model = methodModel()
        model.onFailed(refusedOutcome())
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `the reason the SDK gave is what the card shows`() {
        val model = captureModel()
        model.onFailed(refusedOutcome())
        assertTrue(
            model.uiState.value.resultText
                .contains("Insufficient funds"),
        )
    }

    @Test
    fun `the last step stays finished once the signal has been consumed`() {
        // The signal is cleared as navigation consumes it. Read as progress, that left the step list saying
        // "waiting" under a payment the screen was still showing the result of.
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        model.outcomeShown()

        assertTrue(model.uiState.value.finished)
    }

    @Test
    fun `a stored method leaves its step finished after the signal is consumed`() {
        val model = methodModel()
        model.onCompleted(storedMethodOutcome())
        model.outcomeShown()

        assertTrue(model.uiState.value.finished)
    }

    @Test
    fun `a failure leaves the last step unfinished`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        model.outcomeShown()

        model.onFailed(refusedOutcome())

        assertFalse(model.uiState.value.finished)
    }

    @Test
    fun `starting over hands the capture screen back to the form step`() {
        // A finished step draws no controls, so the form is off the screen until this runs.
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        model.outcomeShown()

        model.startOver()

        assertFalse("the form step is still finished", model.uiState.value.finished)
        assertEquals("", model.uiState.value.resultText)
        assertNull(model.uiState.value.lastResult)
    }

    @Test
    fun `starting over hands the stored-method screen back too`() {
        val model = methodModel()
        model.onCompleted(storedMethodOutcome())
        model.outcomeShown()

        model.startOver()

        assertFalse(model.uiState.value.finished)
        assertNull(model.uiState.value.storedMethod)
    }

    @Test
    fun `consuming the signal clears it, so returning does not push again`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        assertTrue(model.uiState.value.outcomeReady)
        model.outcomeShown()
        assertFalse(model.uiState.value.outcomeReady)
    }

    @Test
    fun `a refusal leaves the sheet open, because it holds the values the service refused`() {
        // The sheet's form is its own instance, separate from the one under it. Closed on a refusal, the payer
        // is left looking at an empty form and the values the service named are gone.
        val model = captureModel()
        model.openSheet()

        model.onFailed(refusedOutcome())

        assertTrue("the sheet closed over a refusal", model.uiState.value.isSheetOpen)
    }

    @Test
    fun `a stored-method refusal leaves that sheet open too`() {
        val model = methodModel()
        model.openSheet()

        model.onFailed(refusedOutcome())

        assertTrue("the sheet closed over a refusal", model.uiState.value.isSheetOpen)
    }

    @Test
    fun `a success closes the sheet, because there is nothing left to correct`() {
        val model = captureModel()
        model.openSheet()

        model.onCompleted(capturedPaymentOutcome())

        assertFalse("the sheet stayed open over a completed payment", model.uiState.value.isSheetOpen)
    }

    private fun approvalWithoutTransaction() =
        PayInSubmissionState.Succeeded
            .Payment(
                PayInResult("A0000", reason = null, explanation = null, action = null, transaction = null),
            ).toOutcome()
}
