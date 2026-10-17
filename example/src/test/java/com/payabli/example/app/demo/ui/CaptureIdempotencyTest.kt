package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.PayInOutcome
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The payment reference a capture carries, per payment.
 *
 * An unknown outcome keeps it, since a retry names the payment it repeats, and an answer replaces it, since a
 * second payment needs its own name. Which failures keep it is the SDK's answer, pinned in `PayInOutcomeTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureIdempotencyTest {
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun captureModel(
        demoCustomer: DemoCustomerSetting = DemoCustomerSetting(SampleIdentity.from("Test Device")),
    ) = CaptureViewModel(
        identity = SampleIdentity.from("Test Device"),
        demoCustomer = demoCustomer,
        startup = readyStartup(),
        diagnostics = DiagnosticsStore(),
        diagnosticsEnabled = true,
        configuration = DemoConfiguration.fromBuildConfig(),
    )

    private fun refusal(
        keepsItsPaymentReference: Boolean,
        isOutcomeUnknown: Boolean = false,
    ) = PayInOutcome.Refused(
        error = PaymentError.Payabli("Refused", null),
        diagnostic = "PayInException.Refused(code=D0001)",
        keepsItsPaymentReference = keepsItsPaymentReference,
        isOutcomeUnknown = isOutcomeUnknown,
    )

    private fun attemptOf(model: CaptureViewModel) =
        (model.uiState.value.operation.operation as PayabliPayInOperation.Capture).options.attempt

    private fun referenceOf(model: CaptureViewModel) = model.uiState.value.operation.paymentReference

    @Test
    fun `the first attempt names its payment`() {
        assertTrue("a capture went out naming no payment", !referenceOf(captureModel()).isNullOrBlank())
    }

    @Test
    fun `an approved payment keeps its reference until the payer asks for another`() {
        // The form leaves the screen when a payment completes, so nothing can be submitted under this reference
        // until `startOver` names a new one.
        val model = captureModel()
        val first = referenceOf(model)

        model.onCompleted(capturedPaymentOutcome())

        assertEquals(first, referenceOf(model))
    }

    @Test
    fun `an answered payment's reference is replaced, so the next attempt is a new payment`() {
        val model = captureModel()
        val first = referenceOf(model)

        model.onFailed(refusal(keepsItsPaymentReference = false))

        assertTrue("an answered attempt kept its reference", first != referenceOf(model))
    }

    @Test
    fun `a reference its payment still needs is kept`() {
        // A new reference would send the retry as a second payment instead of a repeat the service can recognise.
        val model = captureModel()
        val first = referenceOf(model)

        model.onFailed(refusal(keepsItsPaymentReference = true))

        assertEquals(first, referenceOf(model))
    }

    @Test
    fun `starting over asks for another payment, so it names another`() {
        // The payer is asking for a second payment, which needs a name of its own. An unknown outcome keeps the
        // reference for a retry; this is not a retry.
        val model = captureModel()
        model.onFailed(refusal(keepsItsPaymentReference = true))
        val kept = referenceOf(model)

        model.startOver()

        assertTrue("the next payment went out under the kept reference", kept != referenceOf(model))
    }

    @Test
    fun `an unknown outcome makes the next submission its retry`() {
        val model = captureModel()
        val first = referenceOf(model)

        model.onFailed(refusal(keepsItsPaymentReference = true, isOutcomeUnknown = true))

        assertEquals(first, referenceOf(model))
        assertEquals(PayabliAttempt.Retry, attemptOf(model))
    }

    @Test
    fun `a submission refused while another is in flight leaves the operation as it was`() {
        // That request is still running, so nothing is known about the payment yet.
        val model = captureModel()
        val before = model.uiState.value.operation

        model.onFailed(refusal(keepsItsPaymentReference = true))

        assertSame(before, model.uiState.value.operation)
        assertEquals(PayabliAttempt.NewPayment, attemptOf(model))
    }

    @Test
    fun `an answer after a retry starts a new payment`() {
        val model = captureModel()
        model.onFailed(refusal(keepsItsPaymentReference = true, isOutcomeUnknown = true))

        model.onFailed(refusal(keepsItsPaymentReference = false))

        assertEquals(PayabliAttempt.NewPayment, attemptOf(model))
    }

    @Test
    fun `a retry refused for describing another payment stays the retry`() {
        val model = captureModel()
        model.onFailed(refusal(keepsItsPaymentReference = true, isOutcomeUnknown = true))
        val retry = model.uiState.value.operation

        model.onFailed(refusal(keepsItsPaymentReference = true))

        assertSame(retry, model.uiState.value.operation)
        assertEquals(PayabliAttempt.Retry, attemptOf(model))
    }

    @Test
    fun `the demo customer switch does not replace an open retry`() {
        // The retry is the same payment, so it keeps what it was first sent with.
        val demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device"))
        val model = captureModel(demoCustomer)
        model.onFailed(refusal(keepsItsPaymentReference = true, isOutcomeUnknown = true))
        val retry = model.uiState.value.operation

        demoCustomer.setSuppliesDemoCustomer(!demoCustomer.suppliesDemoCustomer.value)

        assertSame(retry, model.uiState.value.operation)
        assertEquals(PayabliAttempt.Retry, attemptOf(model))
    }

    @Test
    fun `the demo customer switch rebuilds a payment that is not a retry`() {
        val demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device"))
        val model = captureModel(demoCustomer)
        val before = model.uiState.value.operation

        demoCustomer.setSuppliesDemoCustomer(!demoCustomer.suppliesDemoCustomer.value)

        assertNotSame(before, model.uiState.value.operation)
    }
}
