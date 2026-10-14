package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.PayInReadOutcome
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
import com.payabli.example.app.sdk.toPaymentError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** When the capture screen offers to read its transaction back, and what a read leaves on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadControlTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `nothing to read before a payment has been taken`() {
        assertNull(captureModel().uiState.value.readableTransactionId)
    }

    @Test
    fun `a completed payment offers its own transaction`() {
        val model = captureModel()

        model.onCompleted(capturedPaymentOutcome())

        assertEquals("101-abc", model.uiState.value.readableTransactionId)
    }

    @Test
    fun `a read shows the status and the transaction it read`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val taken = model.uiState.value

        val after =
            with(model) {
                taken.copy(isReading = true).afterReading(PayInReadOutcome.Read("voided", 5, "101-abc"))
            }

        assertFalse(after.isReading)
        assertTrue(after.resultText, after.resultText.startsWith("Status: voided"))
        assertTrue(after.resultText, after.resultText.contains("Status number: 5"))
        assertTrue(after.resultText, after.resultText.contains("Payment transaction: 101-abc"))
    }

    @Test
    fun `a failed read says why and offers the control again`() {
        val model = captureModel()
        model.onCompleted(capturedPaymentOutcome())
        val taken = model.uiState.value

        val after =
            with(model) {
                taken
                    .copy(
                        isReading = true,
                    ).afterReading(PayInReadOutcome.Failed(IllegalStateException().toPaymentError()))
            }

        assertFalse(after.isReading)
        assertEquals("101-abc", after.readableTransactionId)
        assertTrue(after.resultText, after.resultText.startsWith("✗"))
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
