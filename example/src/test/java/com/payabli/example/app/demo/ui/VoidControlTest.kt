package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.example.app.sdk.refusedOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * When the capture screen offers to void, and when it stops.
 *
 * The control is drawn from `voidableTransactionId` alone, so these are the whole of its visibility rule.
 * What a void does when tapped needs a flow, which a JVM test cannot build, so that half is the manual tier.
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
