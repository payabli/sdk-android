package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.capturedPaymentOutcome
import com.payabli.example.app.payment.readyStartup
import com.payabli.example.app.payment.refusedOutcome
import com.payabli.example.app.payment.storedMethodOutcome
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Turning diagnostics off has to stop the recording, not just hide the panel.
 *
 * A flag that reaches the UI state alone leaves every request line recorded and held in memory while the
 * panel is hidden, and the screen looks the same either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsGateTest {
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private fun methodModel(
        store: DiagnosticsStore,
        enabled: Boolean,
    ) = PaymentMethodViewModel(
        setup = DemoForms.storePaymentMethod(),
        startup = readyStartup(),
        diagnostics = store,
        diagnosticsEnabled = enabled,
        configuration = DemoConfiguration.fromBuildConfig(),
    )

    private fun captureModel(
        store: DiagnosticsStore,
        enabled: Boolean,
    ) = CaptureViewModel(
        setup = DemoForms.capture(),
        startup = readyStartup(),
        diagnostics = store,
        diagnosticsEnabled = enabled,
        configuration = DemoConfiguration.fromBuildConfig(),
    )

    @Test
    fun `payment method records nothing at all when diagnostics are off`() {
        val store = DiagnosticsStore()
        val model = methodModel(store, enabled = false)

        model.onCompleted(storedMethodOutcome())
        model.onFailed(refusedOutcome())

        assertEquals(emptyList<String>(), store.messages.value)
    }

    @Test
    fun `capture records nothing at all when diagnostics are off`() {
        val store = DiagnosticsStore()
        val model = captureModel(store, enabled = false)

        model.onCompleted(capturedPaymentOutcome())
        model.onFailed(refusedOutcome())

        assertEquals(emptyList<String>(), store.messages.value)
    }

    @Test
    fun `payment method records both outcomes when diagnostics are on`() {
        // The other half of the gate. The two tests above also pass against one that records nothing ever.
        val store = DiagnosticsStore()
        val model = methodModel(store, enabled = true)

        model.onCompleted(storedMethodOutcome())
        model.onFailed(refusedOutcome())

        assertEquals(2, store.messages.value.size)
    }

    @Test
    fun `capture records both outcomes when diagnostics are on`() {
        val store = DiagnosticsStore()
        val model = captureModel(store, enabled = true)

        model.onCompleted(capturedPaymentOutcome())
        model.onFailed(refusedOutcome())

        assertEquals(2, store.messages.value.size)
    }

    @Test
    fun `nothing a diagnostics line carries is a card detail`() {
        // The panel is on screen in the demo and copied into bug reports. The SDK hands back a stored-method
        // identifier and a transaction identifier, and a recorded line must stay that side of the boundary.
        val store = DiagnosticsStore()
        val model = captureModel(store, enabled = true)

        model.onCompleted(capturedPaymentOutcome())

        val recorded = store.messages.value.joinToString("\n")
        listOf("4111", "1.10", "Insufficient").forEach {
            assertEquals("$it is in a recorded line:\n$recorded", false, recorded.contains(it))
        }
    }

    @Test
    fun `a refusal records its code and none of the service text`() {
        // The failure path, which the success path above cannot speak for. `PayInFailure.reason` and
        // `explanation` are displayable and never loggable, because the service echoes submitted values into
        // some of them: a postal code, a name, an account number typed into the wrong field.
        val store = DiagnosticsStore()
        captureModel(store, enabled = true).onFailed(refusedOutcome())
        methodModel(store, enabled = true).onFailed(refusedOutcome())

        val recorded = store.messages.value.joinToString("\n")
        listOf("Insufficient funds", "Try another card").forEach {
            assertEquals("the service said \"$it\" and it was recorded:\n$recorded", false, recorded.contains(it))
        }
        assertEquals("the refusal recorded no code to identify it by", true, recorded.contains("D0001"))
    }
}
