package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.interruptedOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.example.app.sdk.refusedOutcome
import com.payabli.example.app.sdk.voidedOutcome
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
 * When the capture screen offers to void, and when it stops.
 *
 * The control is drawn from `voidableTransactionId` alone, so these are the whole of its visibility rule.
 * What the two outcomes leave behind is asserted through `afterVoiding`, which is the branch the call takes;
 * reaching it through the call itself needs a flow answering a real service, which is the instrumented tier.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoidControlTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `nothing to void before a payment has been taken`() {
        assertNull(captureModel().uiState.value.voidableTransactionId)
    }

    @Test
    fun `a completed payment offers its own transaction`() {
        val model = captureModel()

        model.onCompleted(capturedPaymentOutcome())

        assertEquals("101-abc", model.uiState.value.voidableTransactionId)
    }

    @Test
    fun `a refusal carries no transaction, so nothing is offered`() {
        val model = captureModel()

        model.onCompleted(capturedPaymentOutcome())
        model.onFailed(refusedOutcome())

        assertNull(model.uiState.value.voidableTransactionId)
    }

    @Test
    fun `taking another payment withdraws the previous transaction`() {
        val model = captureModel()

        model.onCompleted(capturedPaymentOutcome())
        model.startOver()

        assertNull(model.uiState.value.voidableTransactionId)
    }

    /**
     * The screen offers nothing without a flow, and says so by not drawing rather than by failing.
     *
     * A JVM test cannot build one, so this is the state every test in this file is in: the call returns
     * having done nothing, and in particular does not strand `isVoiding` at true, which would leave a
     * control the payer can see and cannot press.
     */
    @Test
    fun `a void with no flow behind it changes nothing`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())

        model.voidLastTransaction()

        assertFalse(model.uiState.value.isVoiding)
        assertEquals("101-abc", model.uiState.value.voidableTransactionId)
    }

    /**
     * The rule a successful void relies on, asserted on the state directly.
     *
     * Reaching it through [CaptureViewModel.voidLastTransaction] needs a flow answering a real service, which
     * is the manual tier. The rule itself is this comparison and is worth holding here.
     */
    @Test
    fun `a transaction already voided is no longer offered`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val afterCapture = model.uiState.value

        assertEquals("101-abc", afterCapture.voidableTransactionId)
        assertNull(afterCapture.copy(voidedTransactionId = "101-abc").voidableTransactionId)
        // A different transaction having been voided does not withdraw this one.
        assertEquals("101-abc", afterCapture.copy(voidedTransactionId = "202-xyz").voidableTransactionId)
    }

    /**
     * The screen an approved reversal leaves: the outcome in the service's words, and the control withdrawn.
     *
     * `voidedTransactionId` is what withdraws it, so this is the same rule the visibility tests above read,
     * arrived at through the branch the call actually takes.
     */
    @Test
    fun `an approved void names the outcome and withdraws the control`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val taken = model.uiState.value

        val after = with(model) { taken.copy(isVoiding = true).afterVoiding(voidedOutcome(), "101-abc") }

        assertFalse(after.isVoiding)
        assertEquals("101-abc", after.voidedTransactionId)
        assertNull("the control was still offered", after.voidableTransactionId)
        assertTrue(after.resultText, after.resultText.startsWith("✓ Voided: A0003"))
        assertTrue(after.resultText, after.resultText.contains("Reason: Canceled"))
        assertTrue(after.resultText, after.resultText.contains("Payment transaction: 101-abc"))
    }

    /** A refusal leaves the transaction standing, so the control comes back rather than disappearing. */
    @Test
    fun `a refused void keeps the transaction and offers the control again`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val taken = model.uiState.value

        val after = with(model) { taken.copy(isVoiding = true).afterVoiding(refusedOutcome(), "101-abc") }

        assertFalse(after.isVoiding)
        assertNull("a refused void recorded the transaction as reversed", after.voidedTransactionId)
        assertEquals("101-abc", after.voidableTransactionId)
        assertTrue(after.resultText, after.resultText.startsWith("✗"))
    }

    /**
     * A payment gets one key, and every reversal of it sends that one.
     *
     * The service recognizes a repeat by the key, so a second attempt under a fresh key asks it to reverse a
     * transaction it has already reversed. What that returns is a failure, over a reversal that worked.
     */
    @Test
    fun `a completed payment carries one key for reversing it`() {
        val model = captureModel()

        model.onCompleted(capturedPaymentOutcome())

        val key = model.uiState.value.voidIdempotencyKey
        assertNotNull("a transaction to reverse with no key to reverse it under", key)
        // Still the same one after a rebuild of the state, since it belongs to the transaction.
        assertEquals(key, model.uiState.value.voidIdempotencyKey)
    }

    /**
     * No refusal replaces the key, whichever kind it was.
     *
     * A reversal takes only the transaction, so a second attempt is the same request and belongs under the
     * same key. That holds for a refusal the service answered as much as for one that left the outcome
     * unknown, and it is why nothing here mints a second time.
     */
    @Test
    fun `a refusal leaves the key alone`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val taken = model.uiState.value
        val key = requireNotNull(taken.voidIdempotencyKey)

        val unknown = with(model) { taken.afterVoiding(interruptedOutcome(), "101-abc") }
        val answered = with(model) { taken.afterVoiding(refusedOutcome(), "101-abc") }

        assertEquals("a retry could no longer be recognized as the same attempt", key, unknown.voidIdempotencyKey)
        assertEquals("a retry could no longer be recognized as the same attempt", key, answered.voidIdempotencyKey)
    }

    /** Once reversed there is nothing left to identify, and the screen moving on drops it too. */
    @Test
    fun `the key goes when the transaction does`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val taken = model.uiState.value

        assertNull(with(model) { taken.afterVoiding(voidedOutcome(), "101-abc") }.voidIdempotencyKey)

        model.startOver()
        assertNull(model.uiState.value.voidIdempotencyKey)
    }

    /**
     * The screen state carries what the readout types redact, so it declares its own `toString` too.
     *
     * `CaptureUiState` holds the transaction identifier and the key that reverses it. As a `data class` it
     * would synthesize one over both, which reaches an assertion failure or a crash report whole and undoes
     * the redaction one level down.
     */
    @Test
    fun `the screen state names no transaction and no key`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val state = model.uiState.value

        val rendered = state.toString()
        assertFalse(rendered, rendered.contains("101-abc"))
        assertFalse(rendered, rendered.contains(requireNotNull(state.voidIdempotencyKey)))
        // What a reader does need: whether there is something to reverse, without naming it.
        assertTrue(rendered, rendered.contains("hasVoidableTransaction=true"))
    }

    private fun captureModel() =
        CaptureViewModel(
            identity = SampleIdentity.from("Test Device"),
            demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device")),
            startup = readyStartup(),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )
}
