package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.capture.CaptureUiState
import com.payabli.example.app.demo.ui.capture.CaptureViewModel
import com.payabli.example.app.sdk.PayInFormSummary
import com.payabli.example.app.sdk.readyStartup
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * What the screen holds and what it would submit describe one payment.
 *
 * Two seams join here and each is covered alone: `DemoCustomerSettingTest` covers the switch, and
 * `CaptureRequestTest` covers the request built from a value. Neither reaches the wiring between them, so
 * removing the collector below makes the Configuration switch a no-op, and dropping either state update in
 * `startOver` leaves the form showing a figure the request does not charge. Both leave every other test green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureStateAgreesTest {
    private val identity = SampleIdentity.from("Test Device")

    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun `flipping the shared switch changes what an open screen would submit`() {
        // The switch lives on another screen, so a payment already configured has to follow it. Read off the
        // operation rather than the setting: what matters is the request, and the setting is what it reads.
        val setting = DemoCustomerSetting(identity)
        val model = captureModel(setting)

        assertEquals(identity.customerNumber, customerNumberOf(model.uiState.value))

        setting.setSuppliesDemoCustomer(false)
        assertEquals(null, customerNumberOf(model.uiState.value))

        setting.setSuppliesDemoCustomer(true)
        assertEquals(identity.customerNumber, customerNumberOf(model.uiState.value))
    }

    @Test
    fun `a second payment shows the figure it charges`() {
        val model = captureModel(DemoCustomerSetting(identity))

        model.startOver()

        assertAgrees(model.uiState.value)
    }

    @Test
    fun `the first payment shows the figure it charges`() {
        assertAgrees(captureModel(DemoCustomerSetting(identity)).uiState.value)
    }

    /**
     * The rows the form reads back, the amount the state holds and the total the request charges are one figure.
     *
     * The rows carry the amount before the fee, so they are added: a payer shown one figure and charged another
     * is the defect, and it arrived once already as two literals in two files.
     */
    private fun assertAgrees(state: CaptureUiState) {
        val rows = PayInFormSummary.rows(state.setup.configuration).associate { it.label to it.value }
        val shown = dollars(state.setup.configuration.summaryValueFor(PayInField.Amount))
        val fee = dollars(state.setup.configuration.summaryValueFor(PayInField.ServiceFee))

        assertEquals("the rows do not add up to the amount the state holds: $rows", state.amount, shown + fee)
        assertEquals("the request does not charge what the state holds", state.amount, totalOf(state))
    }

    private fun captureModel(setting: DemoCustomerSetting) =
        CaptureViewModel(
            identity = identity,
            demoCustomer = setting,
            startup = readyStartup(),
            diagnostics = DiagnosticsStore(),
            diagnosticsEnabled = true,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    private fun captureOf(state: CaptureUiState) = state.operation.operation as PayabliPayInOperation.Capture

    private fun customerNumberOf(state: CaptureUiState) = captureOf(state).options.customerData?.customerNumber

    private fun totalOf(state: CaptureUiState) = captureOf(state).options.paymentDetails.totalAmount

    private fun dollars(row: String): BigDecimal = BigDecimal(row.removePrefix("$").trim())
}
