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
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliFieldError
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.PayInRoutes
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.APPROVED_TRANSACTION
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
import com.payabli.sdk.payin.payment.TEST_ENTRY_POINT
import com.payabli.sdk.payin.payment.testOptions
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card-not-present funnel, driven through the real composable.
 *
 * Three points, and the interesting property is that they are the same three whatever the payer chose and
 * whatever the caller asked for: one form serves card and bank, and one submit serves capturing, authorizing
 * and storing. A per-instrument or per-operation call site would be four places to keep in step.
 *
 * Bank rather than card, so every field can be typed: a card expiry is picked from a dialog.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormTelemetryInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    private fun listen() {
        TelemetryRecorders.install { event, properties -> recorded += event to properties }
    }

    @Test
    fun theFormAppearingIsReportedOnce() {
        listen()

        showForm()
        rule.waitForIdle()

        assertEquals(
            listOf(TelemetryEvents.FORM_PRESENTED to mapOf(TelemetryProperty.STEP.key to "capture")),
            recorded,
        )
    }

    /**
     * A recomposition is not a second presentation.
     *
     * Typing recomposes the form on every character, and a report keyed on anything the caller can hand over
     * fresh each time would count one opened form hundreds of times.
     */
    @Test
    fun typingDoesNotReportTheFormAgain() {
        listen()

        showForm()
        fillBank()
        rule.waitForIdle()

        assertEquals(1, recorded.count { it.first == TelemetryEvents.FORM_PRESENTED })
    }

    /**
     * A host that swaps the flow on screen has presented a second form.
     *
     * This composable recomputes the instruments it offers when the operation changes, so the screen the
     * payer sees changes with it. Keyed on nothing, the funnel then read as a capture presented and a stored
     * method submitted, with no presentation for the flow that was actually filled in.
     */
    @Test
    fun changingTheOperationReportsTheFlowThatReplacedIt() {
        listen()

        val operation = mutableStateOf<PayabliPayInOperation>(PayabliPayInOperation.Capture(testOptions()))
        showForm(operation = operation)
        rule.runOnUiThread { operation.value = PayabliPayInOperation.StoreMethod() }
        rule.waitForIdle()

        assertEquals(
            listOf("capture", "store_method"),
            recorded
                .filter { it.first == TelemetryEvents.FORM_PRESENTED }
                .mapNotNull { it.second[TelemetryProperty.STEP.key] },
        )
    }

    /**
     * A second flow is a second form, whatever the operation says.
     *
     * One form per flow is the contract this composable states: the typed values are the flow's, so a
     * replaced flow is an emptied form in front of the payer. Keyed on the step alone, a host moving between
     * two captures reported one presentation and two submissions.
     */
    @Test
    fun replacingTheFlowReportsTheFormAgain() {
        listen()

        val flow = mutableStateOf(aFlow())
        showForm(flow = flow)
        rule.runOnUiThread { flow.value = aFlow() }
        rule.waitForIdle()

        assertEquals(2, recorded.count { it.first == TelemetryEvents.FORM_PRESENTED })
    }

    /** And the same flow handed over as a fresh instance is the same presentation. */
    @Test
    fun aNewInstanceOfTheSameOperationDoesNotReportAgain() {
        listen()

        val operation = mutableStateOf<PayabliPayInOperation>(PayabliPayInOperation.Capture(testOptions()))
        showForm(operation = operation)
        rule.runOnUiThread { operation.value = PayabliPayInOperation.Capture(testOptions()) }
        rule.waitForIdle()

        assertEquals(1, recorded.count { it.first == TelemetryEvents.FORM_PRESENTED })
    }

    /**
     * A form built under a session that had reporting off stays off, whatever replaced that session.
     *
     * A form outlives a re-initialize: the flow holds the retired session's transport and the composition
     * holds the flow. Reported through whichever recorder is installed, presenting it after an enabled
     * successor arrives emits events the host had opted out of, and under the successor's identity.
     */
    @Test
    fun aFormFromAnOptedOutSessionReportsNothing() {
        listen()

        showForm(telemetry = aSession(telemetryEnabled = false))
        fillBank()
        submit()
        rule.waitForIdle()

        assertEquals(emptyList<Pair<String, Map<String, String>>>(), recorded)
    }

    /** And the same form under a session that allows it reports the funnel as usual. */
    @Test
    fun aFormFromAnEnabledSessionReportsAsUsual() {
        listen()

        showForm(telemetry = aSession(telemetryEnabled = true))
        rule.waitForIdle()

        assertEquals(1, recorded.count { it.first == TelemetryEvents.FORM_PRESENTED })
    }

    private fun aSession(telemetryEnabled: Boolean) =
        TelemetrySessionContext(
            entryPoint = TEST_ENTRY_POINT,
            environment = PayabliEnvironment.SANDBOX,
            telemetryEnabled = telemetryEnabled,
            sessionId = "a-session",
            device = TelemetryDeviceContext.NONE,
        )

    @Test
    fun submittingIsReportedBeforeAnythingIsSent() {
        listen()

        showForm()
        fillBank()
        recorded.clear()
        submit()

        val submitted = recorded.first()
        assertEquals(TelemetryEvents.FORM_SUBMITTED, submitted.first)
        assertEquals(mapOf(TelemetryProperty.STEP.key to "capture"), submitted.second)
    }

    /**
     * The whole funnel, in order, from one form.
     *
     * What makes it readable is that the outcome arrives under the money-path name for the operation rather
     * than under a fourth form event: a form that is opened, submitted and answered is three events, and any
     * pair of them missing says which half went wrong.
     */
    @Test
    fun aCompletedSubmissionReportsAppearingThenSubmittingThenItsOutcome() {
        listen()

        showForm()
        fillBank()
        submit()
        rule.waitForIdle()

        val names = recorded.map { it.first }
        assertEquals(
            listOf(
                TelemetryEvents.FORM_PRESENTED,
                TelemetryEvents.FORM_SUBMITTED,
                TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
            ),
            names,
        )
        val outcome = recorded.last().second[TelemetryProperty.OUTCOME.key]
        assertTrue("the outcome was not reported: $outcome", outcome == TelemetryProperties.Outcome.APPROVED)
    }

    /**
     * A refused field is reported once, when the refusal arrives.
     *
     * The form's own rules are not a source: `PayInFieldRules.error` answers on every recomposition and
     * calls a half-typed account number too short, so a report from the field boxes would count keystrokes.
     * This is the discrete moment, and it carries which rule refused rather than which field held what.
     */
    @Test
    fun aFieldTheServiceRefusesIsReportedByName() {
        listen()

        showForm(refusing(PayInRoutes.FIELD_ACH_ACCOUNT))
        fillBank()
        recorded.clear()
        submit()
        rule.waitForIdle()

        val validation = recorded.filter { it.first == TelemetryEvents.FORM_VALIDATION_ERROR }
        assertEquals(1, validation.size)
        assertEquals("account_number", validation.single().second[TelemetryProperty.FIELD.key])
    }

    /** Two refused fields are two reports, so what is counted is fields rather than submissions. */
    @Test
    fun everyRefusedFieldIsReported() {
        listen()

        showForm(refusing(PayInRoutes.FIELD_ACH_ACCOUNT, PayInRoutes.FIELD_ACH_ROUTING))
        fillBank()
        recorded.clear()
        submit()
        rule.waitForIdle()

        val fields =
            recorded
                .filter { it.first == TelemetryEvents.FORM_VALIDATION_ERROR }
                .mapNotNull { it.second[TelemetryProperty.FIELD.key] }

        assertEquals(setOf("account_number", "routing_number"), fields.toSet())
    }

    /** What the payer typed is never in the report, whatever the service said about it. */
    @Test
    fun theRefusedValueIsNotReported() {
        listen()

        showForm(refusing(PayInRoutes.FIELD_ACH_ACCOUNT))
        fillBank()
        submit()
        rule.waitForIdle()

        val reported = recorded.flatMap { it.second.values }
        assertTrue(reported.toString(), reported.none { it.contains(TEST_ACCOUNT) })
    }

    /**
     * Nothing here is reachable by a caller: the form reports it, and a host is never asked to.
     *
     * The assertion is the form's own behaviour on a success, which is to empty the instrument. An app that
     * never linked reporting has to reach the same screen as one that did.
     */
    @Test
    fun aFormWithNothingListeningBehavesTheSame() {
        showForm()
        fillBank()
        rule.onNodeWithText(TEST_ACCOUNT).assertExists()

        submit()
        rule.waitForIdle()

        rule.onNodeWithText(TEST_ACCOUNT).assertDoesNotExist()
    }

    private fun refusing(vararg wireFields: String): PayabliTransport =
        FakePayInTransport.failingWith(
            PayabliValidationException(
                httpStatus = 400,
                fieldErrors = wireFields.associateWith { listOf(PayabliFieldError("refused")) },
            ),
        )

    private fun aFlow(
        transport: PayabliTransport = FakePayInTransport.answering(APPROVED_TRANSACTION),
        telemetry: TelemetrySessionContext? = null,
    ) = PayabliPayInPaymentFlow(
        transport = transport,
        entryPoint = TEST_ENTRY_POINT,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        dispatcher = Dispatchers.Main,
        logger = RecordingSdkLogger(),
        telemetry = telemetry,
    )

    private fun showForm(
        transport: PayabliTransport = FakePayInTransport.answering(APPROVED_TRANSACTION),
        operation: State<PayabliPayInOperation> = mutableStateOf(PayabliPayInOperation.Capture(testOptions())),
        telemetry: TelemetrySessionContext? = null,
        flow: State<PayabliPayInPaymentFlow> = mutableStateOf(aFlow(transport, telemetry)),
    ) {
        rule.setContent {
            MaterialTheme {
                PayabliPayInForm(
                    flow = flow.value,
                    operation = operation.value,
                    configuration = bankForm(),
                    onCompleted = {},
                    onFailed = {},
                    onMethodChanged = {},
                )
            }
        }
    }

    private fun bankForm() =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.BankAccount,
            cardSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            labelLayout = PayInLabelLayout.Placeholder,
        )

    private fun fillBank() {
        rule.onNodeWithText(label(PayInField.AccountHolder)).performTextInput("A Payer")
        rule.onNodeWithText(label(PayInField.RoutingNumber)).performTextInput(TEST_ROUTING)
        rule.onNodeWithText(label(PayInField.AccountNumber)).performTextInput(TEST_ACCOUNT)
    }

    private fun submit() {
        rule.onNodeWithText(rule.activity.getString(R.string.payabli_payin_submit)).performClick()
    }

    private fun label(field: PayInField): String =
        rule.activity.getString(
            when (field) {
                PayInField.AccountHolder -> R.string.payabli_payin_field_account_holder
                PayInField.RoutingNumber -> R.string.payabli_payin_field_routing_number
                PayInField.AccountNumber -> R.string.payabli_payin_field_account_number
                else -> error("no label mapped for $field")
            },
        )
}
