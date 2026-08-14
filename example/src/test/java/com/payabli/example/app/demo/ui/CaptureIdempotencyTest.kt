package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.PayInForms
import com.payabli.example.app.sdk.PayInOutcome
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The key a capture carries, per attempt.
 *
 * A capture repeated without one charges twice, which is why an unknown outcome carries the key a retry
 * needs. Repeated with the key of an attempt the service has already answered, a second deliberate payment
 * comes back as the first one's result, so the key cannot simply be fixed for the screen either.
 *
 * Which failures leave a key worth resending is the SDK's answer and is pinned in `PayInOutcomeTest`. This is
 * the screen's half: what it does once told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureIdempotencyTest {
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun captureModel() =
        CaptureViewModel(
            setup = PayInForms.capture(),
            startup = readyStartup(),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    private fun refusal(keepsItsIdempotencyKey: Boolean) =
        PayInOutcome.Refused(
            error = PaymentError.Payabli("Refused", null),
            diagnostic = "PayInException.Refused(code=D0001)",
            keepsItsIdempotencyKey = keepsItsIdempotencyKey,
        )

    private fun keyOf(model: CaptureViewModel) = model.uiState.value.operation.idempotencyKey

    @Test
    fun `the first attempt carries a key`() {
        assertTrue("a capture went out with no idempotency key", !keyOf(captureModel()).isNullOrBlank())
    }

    @Test
    fun `an approved payment keeps its key until the payer asks for another`() {
        // The form leaves the screen when a payment completes, so nothing can be submitted under this key
        // until `startOver` mints a new one. Rotating here would hand a fresh key to a retry of a payment
        // that was taken and described incompletely.
        val model = captureModel()
        val first = keyOf(model)

        model.onCompleted(capturedPaymentOutcome())

        assertEquals(first, keyOf(model))
    }

    @Test
    fun `a spent key is replaced, so the next attempt is a new payment`() {
        val model = captureModel()
        val first = keyOf(model)

        model.onFailed(refusal(keepsItsIdempotencyKey = false))

        assertTrue("an answered attempt kept its key", first != keyOf(model))
    }

    @Test
    fun `a key its attempt still needs is kept`() {
        // A new key would send the retry as a second payment instead of a repeat the service can recognise.
        val model = captureModel()
        val first = keyOf(model)

        model.onFailed(refusal(keepsItsIdempotencyKey = true))

        assertEquals(first, keyOf(model))
    }

    @Test
    fun `starting over asks for another payment, so it carries another key`() {
        // The payer is asking for a second payment, and the service refuses one that arrives under the first
        // one's key. An unknown outcome keeps the key for a retry; this is not a retry.
        val model = captureModel()
        model.onFailed(refusal(keepsItsIdempotencyKey = true))
        val kept = keyOf(model)

        model.startOver()

        assertTrue("the next payment went out under the kept key", kept != keyOf(model))
    }
}
