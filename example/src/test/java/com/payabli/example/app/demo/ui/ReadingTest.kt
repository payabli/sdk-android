package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.FakePayInFlowHandle
import com.payabli.example.app.sdk.PayInReadOutcome
import com.payabli.example.app.sdk.capturedPaymentOutcome
import com.payabli.example.app.sdk.readyStartup
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A read held in flight, and what it keeps the screen from doing meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starting over while a read is out is refused, so its status lands on its own payment`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val model = captureModel(gate)
            model.onCompleted(capturedPaymentOutcome())

            model.readLastTransaction()
            assertTrue(model.uiState.value.isReading)
            model.startOver()
            assertEquals("101-abc", model.uiState.value.readableTransactionId)

            gate.complete(Unit)
            assertFalse(model.uiState.value.isReading)
            assertTrue(
                model.uiState.value.resultText,
                model.uiState.value.resultText
                    .startsWith("Status: captured"),
            )
        }

    @Test
    fun `a void is not sent while a read is out`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val handle = FakePayInFlowHandle(released = gate, readAnswer = { read() }) { _, _ -> voidedOutcome() }
            val model = captureModel(handle)
            model.onCompleted(capturedPaymentOutcome())

            model.readLastTransaction()
            model.voidLastTransaction()

            assertTrue("a void went out during a read", handle.reversals.isEmpty())
            gate.complete(Unit)
        }

    private fun read() = PayInReadOutcome.Read("captured", 1, "101-abc")

    private fun captureModel(gate: CompletableDeferred<Unit>) =
        captureModel(FakePayInFlowHandle(released = gate, readAnswer = { read() }) { _, _ -> voidedOutcome() })

    private fun captureModel(handle: FakePayInFlowHandle) =
        CaptureViewModel(
            identity = SampleIdentity.from("Test Device"),
            demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device")),
            startup = readyStartup(handle),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        ).also { it.checkToken() }
}
