package com.payabli.sdk.payin.ui

import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.PayInFormSuccess
import com.payabli.sdk.payin.PayInPaymentFlow
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.APPROVED_TRANSACTION
import com.payabli.sdk.payin.payment.DECLINED_TRANSACTION
import com.payabli.sdk.payin.payment.GatedPayInTransport
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.TEST_ENTRY_POINT
import com.payabli.sdk.payin.payment.testOptions
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The form holds what the payer entered only for the flow it is showing: another flow empties the previous one.
 *
 * Bank rather than card, so every field can be typed: a card expiry is picked from a dialog.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormFlowSwapInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val first = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))
    private val second = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

    private var payIn by mutableStateOf(first)

    private var onScreen by mutableStateOf(true)

    @Test
    fun anotherFlowEmptiesWhatWasTypedForThePreviousOne() {
        rule.setContent { Form() }
        fillBank()

        rule.runOnIdle { payIn = second }
        rule.waitForIdle()

        assertTrue("the previous flow kept ${first.draft.typed.keys.toList()}", first.draft.typed.isEmpty())
        assertTrue("the previous flow kept its marks", first.draft.rejectedFields.isEmpty())
        assertNull("the previous flow kept its message", first.draft.failureNotice)
        rule.onNodeWithText(HOLDER).assertDoesNotExist()
    }

    @Test
    fun aFlowHandedBackOpensEmpty() {
        rule.setContent { Form() }
        fillBank()

        rule.runOnIdle { payIn = second }
        rule.waitForIdle()
        rule.runOnIdle { payIn = first }

        rule.onNodeWithText(HOLDER).assertDoesNotExist()
        rule.onNodeWithText(TEST_ROUTING).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.payabli_payin_field_account_holder)).assertExists()
    }

    @Test
    fun theSameFlowKeepsWhatWasTypedAcrossARecreation() {
        val restorer = StateRestorationTester(rule)
        restorer.setContent { Form() }
        fillBank()

        restorer.emulateSavedInstanceStateRestore()

        rule.onNodeWithText(HOLDER).assertExists()
        assertEquals(
            HOLDER,
            first.draft.typed.values
                .firstOrNull { it == HOLDER },
        )
    }

    @Test
    fun aFlowSwappedWhileTheFormIsOffScreenEmptiesThePreviousOne() {
        // A tab switch or a screen pushed on top: the form leaves the composition, the host replaces its flow,
        // and the form comes back drawing the new one.
        rule.setContent { Form() }
        fillBank()

        rule.runOnIdle { onScreen = false }
        rule.waitForIdle()
        rule.runOnIdle { payIn = second }
        rule.waitForIdle()
        rule.runOnIdle { onScreen = true }
        rule.waitForIdle()

        assertTrue("the previous flow kept ${first.draft.typed.keys.toList()}", first.draft.typed.isEmpty())
    }

    @Test
    fun twoFormsOnOneScreenKeepTheirOwnEntries() {
        rule.setContent {
            MaterialTheme {
                Column {
                    BankForm(first)
                    BankForm(second)
                }
            }
        }

        rule
            .onAllNodesWithText(string(R.string.payabli_payin_field_account_holder))[0]
            .performTextInput(HOLDER)
        rule
            .onAllNodesWithText(string(R.string.payabli_payin_field_account_holder))[1]
            .performTextInput(OTHER_HOLDER)
        rule.waitForIdle()

        assertEquals(
            HOLDER,
            first.draft.typed.values
                .firstOrNull(),
        )
        assertEquals(
            OTHER_HOLDER,
            second.draft.typed.values
                .firstOrNull(),
        )
    }

    @Test
    fun aRefusalOfThePreviousFlowDrawsNothingOnTheNewOne() {
        val transport = GatedPayInTransport.answering(DECLINED_TRANSACTION)
        val refused = flowOver(transport)
        payIn = refused
        var completed: PayInFormSuccess? = null
        var failed: PayabliException? = null

        rule.setContent { Form(onCompleted = { completed = it }, onFailed = { failed = it }) }
        fillBank()
        rule.onNodeWithText(string(R.string.payabli_payin_submit_capture)).performClick()
        runBlocking { transport.arrived.await() }

        rule.runOnIdle { payIn = second }
        rule.waitForIdle()
        transport.release()
        rule.waitForIdle()

        assertNull("the new flow was told of a completion", completed)
        assertNull("the new flow was told of the previous flow's refusal", failed)
        assertNull("the new flow drew a message", second.draft.failureNotice)
        assertTrue("the new flow marked fields", second.draft.rejectedFields.isEmpty())
        assertNull("the previous flow kept a message", refused.draft.failureNotice)
    }

    @Test
    fun aFlowHandedBackMidFlightStillDeliversItsOutcome() {
        val transport = GatedPayInTransport.answering(DECLINED_TRANSACTION)
        val refused = flowOver(transport)
        payIn = refused
        var failed: PayabliException? = null

        rule.setContent { Form(onFailed = { failed = it }) }
        fillBank()
        rule.onNodeWithText(string(R.string.payabli_payin_submit_capture)).performClick()
        runBlocking { transport.arrived.await() }

        rule.runOnIdle { payIn = second }
        rule.waitForIdle()
        transport.release()
        rule.waitForIdle()
        rule.runOnIdle { payIn = refused }
        rule.waitForIdle()

        assertNotNull("the refusal of the flow handed back was never delivered", failed)
        assertTrue("the flow handed back kept what was typed", refused.draft.typed.isEmpty())
    }

    @Test
    fun twoComposeViewsSharingAnOwnerKeepTheirOwnEntries() {
        val firstLabel = mutableStateOf("")
        val secondLabel = mutableStateOf("")
        rule.runOnUiThread {
            val activity = rule.activity
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            // One function draws both, so the two compositions reach the form by the same path.
            column.addView(formView(first, firstLabel))
            column.addView(formView(second, secondLabel))
            activity.setContentView(column)
        }
        rule.waitForIdle()
        rule.runOnIdle {
            first.draft.enter(PayInField.AccountHolder, HOLDER)
            second.draft.enter(PayInField.AccountHolder, OTHER_HOLDER)
        }

        // A new label recomposes the form itself, which is where it records the draft it draws.
        rule.runOnIdle { firstLabel.value = "first" }
        rule.waitForIdle()
        rule.runOnIdle { secondLabel.value = "second" }
        rule.waitForIdle()

        assertEquals(HOLDER, first.draft.typed[PayInField.AccountHolder])
        assertEquals(OTHER_HOLDER, second.draft.typed[PayInField.AccountHolder])
    }

    @Test
    fun formsInAKeyedListWhoseKeysShareAHashKeepTheirOwnEntries() {
        // "Aa" and "BB" have the same hashCode.
        val labels = mapOf("Aa" to mutableStateOf(""), "BB" to mutableStateOf(""))
        val flows = mapOf("Aa" to first, "BB" to second)
        rule.setContent {
            MaterialTheme {
                Column {
                    flows.forEach { (name, flow) -> key(name) { LabelledBankForm(flow, labels.getValue(name).value) } }
                }
            }
        }
        rule.runOnIdle {
            first.draft.enter(PayInField.AccountHolder, HOLDER)
            second.draft.enter(PayInField.AccountHolder, OTHER_HOLDER)
        }

        rule.runOnIdle { labels.getValue("Aa").value = "first" }
        rule.waitForIdle()
        rule.runOnIdle { labels.getValue("BB").value = "second" }
        rule.waitForIdle()

        assertEquals(HOLDER, first.draft.typed[PayInField.AccountHolder])
        assertEquals(OTHER_HOLDER, second.draft.typed[PayInField.AccountHolder])
    }

    @Test
    fun formsWhoseKeysShareAHashReturningInTheOtherOrderKeepTheirOwnEntries() {
        // "Aa" and "BB" have the same hashCode, and nothing at their position tells the forms apart once they leave.
        var order by mutableStateOf(listOf("Aa" to first, "BB" to second))
        rule.setContent {
            MaterialTheme {
                if (onScreen) {
                    Column { order.forEach { (name, flow) -> key(name) { LabelledBankForm(flow, name) } } }
                }
            }
        }
        rule.runOnIdle {
            first.draft.enter(PayInField.AccountHolder, HOLDER)
            second.draft.enter(PayInField.AccountHolder, OTHER_HOLDER)
        }

        rule.runOnIdle { onScreen = false }
        rule.waitForIdle()
        rule.runOnIdle {
            order = order.reversed()
            onScreen = true
        }
        rule.waitForIdle()

        assertEquals(HOLDER, first.draft.typed[PayInField.AccountHolder])
        assertEquals(OTHER_HOLDER, second.draft.typed[PayInField.AccountHolder])
    }

    @Test
    fun theMethodOnScreenStaysWhereTheNewFlowOffersItAndTellsNothing() {
        rule.setContent { MethodForm() }
        tapBank()
        told.clear()

        rule.runOnIdle { payIn = second }
        rule.waitForIdle()

        rule.onNodeWithText(string(R.string.payabli_payin_field_routing_number)).assertExists()
        assertTrue("a swap that kept the method told the host $told", told.isEmpty())
    }

    @Test
    fun aNewFlowThatDoesNotOfferTheMethodOpensOnItsStartingMethodAndTellsTheHostOnce() {
        rule.setContent { MethodForm() }
        tapBank()
        told.clear()

        rule.runOnIdle {
            payIn = second
            methods = cardOnly
        }
        rule.waitForIdle()

        rule.onNodeWithText(string(R.string.payabli_payin_field_routing_number)).assertDoesNotExist()
        assertEquals(listOf(PayInMethodType.Card), told)
    }

    @Test
    fun aFlowHandedBackIsNotToldAMoveTheFormAlreadyReported() {
        rule.setContent { MethodForm() }
        tapBank()
        rule.runOnIdle {
            payIn = second
            methods = cardOnly
        }
        rule.waitForIdle()
        told.clear()

        rule.runOnIdle {
            payIn = first
            methods = cardAndBank
        }
        rule.waitForIdle()

        assertTrue("the flow handed back told the host $told", told.isEmpty())
    }

    @Composable
    private fun MethodForm() {
        MaterialTheme {
            PayabliPayInForm(
                payIn = payIn,
                operation = PayabliPayInOperation.Capture(testOptions()),
                configuration = methods,
                onCompleted = {},
                onFailed = {},
                onMethodChanged = { told += it },
            )
        }
    }

    private fun tapBank() {
        rule
            .onNode(hasClickAction() and hasText(string(R.string.payabli_payin_method_bank_account)))
            .performClick()
        rule.waitForIdle()
    }

    private val told = mutableListOf<PayInMethodType>()

    private val cardAndBank =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.Card,
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            labelLayout = PayInLabelLayout.Placeholder,
        )

    private val cardOnly = cardAndBank.copy(allowedMethods = listOf(PayInMethodType.Card))

    private var methods by mutableStateOf(cardAndBank)

    private fun formView(
        flow: PayInPaymentFlow,
        label: MutableState<String>,
    ): ComposeView =
        ComposeView(rule.activity).apply {
            id = View.generateViewId()
            setContent { MaterialTheme { LabelledBankForm(flow, label.value) } }
        }

    @Composable
    private fun LabelledBankForm(
        flow: PayInPaymentFlow,
        submitLabel: String,
    ) {
        PayabliPayInForm(
            payIn = flow,
            operation = PayabliPayInOperation.Capture(testOptions()),
            configuration = bankForm,
            labels = PayInFormLabels(title = submitLabel),
            onCompleted = {},
            onFailed = {},
            onMethodChanged = {},
        )
    }

    @Composable
    private fun Form(
        onCompleted: (PayInFormSuccess) -> Unit = {},
        onFailed: (PayabliException) -> Unit = {},
    ) {
        MaterialTheme {
            if (onScreen) BankForm(payIn, onCompleted, onFailed)
        }
    }

    @Composable
    private fun BankForm(
        flow: PayInPaymentFlow,
        onCompleted: (PayInFormSuccess) -> Unit = {},
        onFailed: (PayabliException) -> Unit = {},
    ) {
        PayabliPayInForm(
            payIn = flow,
            operation = PayabliPayInOperation.Capture(testOptions()),
            configuration = bankForm,
            onCompleted = onCompleted,
            onFailed = onFailed,
            onMethodChanged = {},
        )
    }

    private val bankForm =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.BankAccount,
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            labelLayout = PayInLabelLayout.Placeholder,
        )

    private fun flowOver(transport: PayabliTransport): PayInPaymentFlow =
        PayInPaymentFlow.over(
            transport = transport,
            entryPoint = TEST_ENTRY_POINT,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            dispatcher = Dispatchers.Main,
            logger = RecordingSdkLogger(),
        )

    private fun fillBank() {
        type(R.string.payabli_payin_field_account_holder, HOLDER)
        type(R.string.payabli_payin_field_routing_number, TEST_ROUTING)
        type(R.string.payabli_payin_field_account_number, TEST_ACCOUNT)
    }

    private fun type(
        labelResource: Int,
        text: String,
    ) {
        rule.onNodeWithText(string(labelResource)).performTextInput(text)
        rule.waitForIdle()
    }

    private fun string(resource: Int) = rule.activity.getString(resource)

    private companion object {
        const val HOLDER = "Ada Lovelace"
        const val OTHER_HOLDER = "Grace Hopper"
    }
}
