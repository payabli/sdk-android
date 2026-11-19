package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.payabli.sdk.payin.form.PayInFormConfiguration
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
