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
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.PayInFormSuccess
import com.payabli.sdk.payin.PayInPaymentFlow
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.payment.APPROVED_TRANSACTION
import com.payabli.sdk.payin.payment.GatedPayInTransport
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.STORED_METHOD
import com.payabli.sdk.payin.payment.TEST_ENTRY_POINT
import com.payabli.sdk.payin.payment.testOptions
import com.payabli.sdk.payin.util.extensions.formatAmount
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.Locale

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
        var delivered: PayInFormSuccess? = null

        showForm(flow) { delivered = it }
        fillBank()
        submit()

        rule.waitForIdle()
        assertEquals("the caller was not given the outcome", "A0000", delivered?.let { code(it) })
        assertEquals(
            "the flow kept an outcome the caller already has",
            PayInSubmissionState.Idle,
            flow.submissionState.value,
        )
    }

    @Test
    fun aStoreDeliversTheStoredMethod() {
        var delivered: PayInFormSuccess? = null

        showForm(
            flowOver(FakePayInTransport.answering(STORED_METHOD)),
            operation = PayabliPayInOperation.StoreMethod(),
        ) { delivered = it }
        fillBank()
        submit(R.string.payabli_payin_submit_store_method)

        rule.waitForIdle()
        assertTrue("a store was not delivered as a method: $delivered", delivered is PayInFormSuccess.Method)
    }

    @Test
    fun aPaymentWhoseOutcomeIsUnknownReachesOnFailedAsUnsettled() {
        var failure: PayabliException? = null

        showForm(
            flowOver(
                FakePayInTransport.failingWith(PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "dropped")),
            ),
            onFailed = { failure = it },
        ) {}
        fillBank()
        submit()

        rule.waitForIdle()
        assertTrue("an unknown outcome reached the caller as $failure", failure is PayInException.Unsettled)
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
        assertEquals("a throwing caller stranded the outcome", PayInSubmissionState.Idle, flow.submissionState.value)
    }

    @Test
    fun theFormShowsWhatItsOperationCharges() {
        showForm(flowAnswering()) {}

        rule.onNodeWithText(formatAmount(BigDecimal("10"), "USD", Locale.getDefault())).assertExists()
    }

    @Test
    fun aConfigurationChangedMidSubmissionStillDeliversTheOutcome() {
        // A total that ticks or a fee that recalculates hands over a configuration that is not the one this
        // form was composed with. It is still the form that sent the request in flight, and an outcome it
        // does not deliver is one nothing consumes, which refuses every later submission for the process.
        val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
        val flow = flowOver(transport)
        val configuration = mutableStateOf(bankForm())
        var delivered: PayInFormSuccess? = null

        showForm(flow, configuration) { delivered = it }
        fillBank()
        submit()
        runBlocking { transport.arrived.await() }

        rule.runOnUiThread { configuration.value = bankForm(labelLayout = PayInLabelLayout.External) }
        rule.waitForIdle()
        transport.release()

        rule.waitForIdle()
        assertEquals("a changed configuration lost the outcome", "A0000", delivered?.let { code(it) })
        assertEquals("the flow was left holding an outcome", PayInSubmissionState.Idle, flow.submissionState.value)
    }

    private fun code(state: PayInFormSuccess): String? = (state as? PayInFormSuccess.Payment)?.result?.code

    private fun flowAnswering(): PayInPaymentFlow = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

    private fun flowOver(transport: PayabliTransport): PayInPaymentFlow =
        PayInPaymentFlow.over(
            transport = transport,
            entryPoint = TEST_ENTRY_POINT,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            dispatcher = Dispatchers.Main,
            logger = RecordingSdkLogger(),
        )

    private fun bankForm(labelLayout: PayInLabelLayout = PayInLabelLayout.Placeholder) =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.BankAccount,
            cardSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            labelLayout = labelLayout,
        )

    private fun showForm(
        flow: PayInPaymentFlow,
        configuration: State<PayInFormConfiguration> = mutableStateOf(bankForm()),
        operation: PayabliPayInOperation = PayabliPayInOperation.Capture(testOptions()),
        onFailed: (PayabliException) -> Unit = {},
        onCompleted: (PayInFormSuccess) -> Unit,
    ) {
        rule.setContent {
            MaterialTheme {
                PayabliPayInForm(
                    payIn = flow,
                    operation = operation,
                    configuration = configuration.value,
                    onCompleted = onCompleted,
                    onFailed = onFailed,
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

    private fun submit(label: Int = R.string.payabli_payin_submit_capture) {
        rule.onNodeWithText(string(label)).performClick()
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
