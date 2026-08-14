package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.RecordingLogger
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.APPROVED_TRANSACTION
import com.payabli.sdk.payin.payment.GatedPayInTransport
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
import com.payabli.sdk.payin.payment.TEST_ENTRY_POINT
import com.payabli.sdk.payin.payment.testOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The form consumes an outcome once it has delivered it.
 *
 * Nobody outside this module is asked to say an outcome was read: a caller that forgot would have every later
 * submission refused, and the one before it re-delivered after a configuration change. Driven through the real
 * composable rather than `PayInFormContent`, because the consuming is wired where the flow is held.
 *
 * Bank rather than card, so every field can be typed: a card expiry is picked from a dialog.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormConsumesOutcomeInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun anOutcomeIsConsumedOnceTheCallerHasHadIt() {
        val flow = flowAnswering()
        var delivered: PayInSubmissionState.Succeeded? = null

        showForm(flow) { delivered = it }
        fillBank()
        submit()

        rule.waitForIdle()
        assertEquals("the caller was not given the outcome", "A0000", delivered?.let { code(it) })
        assertEquals("the flow kept an outcome the caller already has", PayInSubmissionState.Idle, flow.state.value)
    }

    @Test
    fun aCallerThatThrowsStillLeavesTheFlowUsable() {
        // The function belongs to whoever integrates, so it can throw. Consuming in a `finally` is what keeps
        // that from stranding the outcome, and a stranded outcome refuses every later submission.
        val flow = flowAnswering()

        showForm(flow) { error("the caller threw") }
        fillBank()
        runCatching { submit() }

        rule.waitForIdle()
        assertEquals("a throwing caller stranded the outcome", PayInSubmissionState.Idle, flow.state.value)
    }

    @Test
    fun aConfigurationChangedMidSubmissionStillDeliversTheOutcome() {
        // A total that ticks or a fee that recalculates hands over a configuration that is not the one this
        // form was composed with. It is still the form that sent the request in flight, and an outcome it
        // does not deliver is one nothing consumes, which refuses every later submission for the process.
        val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
        val flow = flowOver(transport)
        val configuration = mutableStateOf(bankForm(total = "1.10"))
        var delivered: PayInSubmissionState.Succeeded? = null

        showForm(flow, configuration) { delivered = it }
        fillBank()
        submit()
        runBlocking { transport.arrived.await() }

        rule.runOnUiThread { configuration.value = bankForm(total = "2.20") }
        rule.waitForIdle()
        transport.release()

        rule.waitForIdle()
        assertEquals("a changed configuration lost the outcome", "A0000", delivered?.let { code(it) })
        assertEquals("the flow was left holding an outcome", PayInSubmissionState.Idle, flow.state.value)
    }

    private fun code(state: PayInSubmissionState.Succeeded): String? =
        (state as? PayInSubmissionState.Succeeded.Payment)?.result?.code

    private fun flowAnswering(): PayabliPayInPaymentFlow = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

    private fun flowOver(transport: PayabliTransport): PayabliPayInPaymentFlow =
        PayabliPayInPaymentFlow(
            transport = transport,
            entryPoint = TEST_ENTRY_POINT,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            dispatcher = Dispatchers.Main,
            logger = RecordingLogger(),
        )

    private fun bankForm(total: String? = null) =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.BankAccount,
            cardSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            labelLayout = PayInLabelLayout.Placeholder,
            summaryValues = total?.let { mapOf(PayInField.Amount to it) }.orEmpty(),
        )

    private fun showForm(
        flow: PayabliPayInPaymentFlow,
        configuration: State<PayInFormConfiguration> = mutableStateOf(bankForm()),
        onCompleted: (PayInSubmissionState.Succeeded) -> Unit,
    ) {
        rule.setContent {
            MaterialTheme {
                PayabliPayInForm(
                    flow = flow,
                    operation = PayabliPayInOperation.Capture(testOptions()),
                    configuration = configuration.value,
                    onCompleted = onCompleted,
                    onFailed = {},
                    onMethodChanged = {},
                )
            }
        }
    }

    private fun fillBank() {
        type(R.string.payabli_payin_field_account_holder, "Ada Lovelace")
        type(R.string.payabli_payin_field_routing_number, TEST_ROUTING)
        type(R.string.payabli_payin_field_account_number, TEST_ACCOUNT)
    }

    private fun submit() {
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).performClick()
        rule.waitForIdle()
    }

    private fun type(
        labelResource: Int,
        text: String,
    ) {
        rule.onNodeWithText(string(labelResource)).performTextInput(text)
        rule.waitForIdle()
    }

    private fun string(resource: Int) = rule.activity.getString(resource)
}
