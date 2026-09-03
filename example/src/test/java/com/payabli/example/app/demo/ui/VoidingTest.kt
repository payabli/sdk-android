package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.FakePayInFlowHandle
import com.payabli.example.app.sdk.PayInFlowHandle
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.example.app.sdk.refusedOutcome
import com.payabli.example.app.sdk.voidedOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reversing a payment, driven through the call a tap makes rather than through the state transition alone.
 *
 * `VoidControlTest` covers when the control is offered and what each outcome leaves behind. This covers the
 * part between them: what reaches the SDK, what the screen looks like while it is in flight, and that the
 * flag clears however the call ends. It needs a handle in flight, which is what `FakePayInFlowHandle` is for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoidingTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a tap sends the transaction on screen, under the key that payment carries`() =
        runTest {
            val handle = FakePayInFlowHandle { _, _ -> voidedOutcome() }
            val model = captureModel(handle)
            model.onCompleted(capturedPaymentOutcome())
            val key = requireNotNull(model.uiState.value.voidIdempotencyKey)

            model.voidLastTransaction()

            assertEquals(listOf("101-abc" to key), handle.reversals)
        }

    @Test
    fun `an approved reversal names the outcome and withdraws the control`() =
        runTest {
            val model = captureModel(FakePayInFlowHandle { _, _ -> voidedOutcome() })
            model.onCompleted(capturedPaymentOutcome())

            model.voidLastTransaction()

            val state = model.uiState.value
            assertFalse("the screen was left reversing", state.isVoiding)
            assertEquals("101-abc", state.voidedTransactionId)
            assertNull("the control was offered again", state.voidableTransactionId)
            assertTrue(state.resultText, state.resultText.startsWith("✓ Voided: A0003"))
        }

    @Test
    fun `a refused reversal reports it and leaves the transaction standing`() =
        runTest {
            val model = captureModel(FakePayInFlowHandle { _, _ -> refusedOutcome() })
            model.onCompleted(capturedPaymentOutcome())

            model.voidLastTransaction()

            val state = model.uiState.value
            assertFalse(state.isVoiding)
            assertNull("a refusal recorded the transaction as reversed", state.voidedTransactionId)
            assertEquals("101-abc", state.voidableTransactionId)
            assertTrue(state.resultText, state.resultText.startsWith("✗"))
        }

    /** The flag the control reads, while the call is still out. */
    @Test
    fun `the screen reports a reversal in flight, and only one goes out`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val handle = FakePayInFlowHandle(released = gate) { _, _ -> voidedOutcome() }
            val model = captureModel(handle)
            model.onCompleted(capturedPaymentOutcome())

            model.voidLastTransaction()
            assertTrue("nothing marked the screen as reversing", model.uiState.value.isVoiding)

            // A second tap while the first is out sends nothing: the guard is read before anything else.
            model.voidLastTransaction()
            assertEquals(1, handle.reversals.size)

            gate.complete(Unit)
            assertFalse(model.uiState.value.isVoiding)
        }

    /**
     * A reversal cancelled with the request out clears the flag on its way through.
     *
     * The screen is left showing a control it will not run otherwise: the coroutine is gone and nothing else
     * writes that flag, so the payer sees "Voiding…" for the life of the screen.
     */
    @Test
    fun `a cancelled reversal does not leave the screen reversing`() =
        runTest {
            val model =
                captureModel(
                    FakePayInFlowHandle { _, _ -> throw CancellationException("the screen went away") },
                )
            model.onCompleted(capturedPaymentOutcome())

            model.voidLastTransaction()

            assertFalse("the control was left showing a reversal in flight", model.uiState.value.isVoiding)
            // Not recorded as reversed: the outcome is unknown, so the transaction still stands.
            assertNull(model.uiState.value.voidedTransactionId)
        }

    /** Starting over is refused mid-reversal, so that coroutine cannot report onto the next attempt. */
    @Test
    fun `starting over is refused while a reversal is in flight`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val model = captureModel(FakePayInFlowHandle(released = gate) { _, _ -> voidedOutcome() })
            model.onCompleted(capturedPaymentOutcome())
            model.voidLastTransaction()

            model.startOver()

            assertEquals(
                "the transaction being reversed was cleared",
                "101-abc",
                model.uiState.value.voidableTransactionId,
            )
            gate.complete(Unit)
        }

    private fun captureModel(handle: PayInFlowHandle) =
        CaptureViewModel(
            identity = SampleIdentity.from("Test Device"),
            demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device")),
            startup = readyStartup(handle),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        ).also { it.checkToken() }
}
