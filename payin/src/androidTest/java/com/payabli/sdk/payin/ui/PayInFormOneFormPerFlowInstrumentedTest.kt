package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.PayInFormSuccess
import com.payabli.sdk.payin.PayInPaymentFlow
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.APPROVED_TRANSACTION
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.TEST_ENTRY_POINT
import com.payabli.sdk.payin.payment.testOptions
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A flow is drawn by one form at a time. A second form over it is a host's mistake: a debuggable app stops on it,
 * and otherwise the second form waits, empty and inert, until the first leaves.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormOneFormPerFlowInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val flow =
        PayInPaymentFlow.over(
            transport = FakePayInTransport.answering(APPROVED_TRANSACTION),
            entryPoint = TEST_ENTRY_POINT,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            dispatcher = Dispatchers.Main,
            logger = RecordingSdkLogger(),
        )

    private var firstOnScreen by mutableStateOf(true)

    private val told = mutableListOf<Any>()

    @Test
    fun aSecondFormOverADrawnFlowStopsADebuggableApp() {
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                rule.setContent { TwoForms(stops = true) }
                rule.waitForIdle()
            }

        assertTrue(
            "the message does not say what the mistake is: ${thrown.message}",
            "PayabliPayIn" in thrown.message.orEmpty(),
        )
    }

    @Test
    fun aSecondFormElsewhereDrawsEmptyAndInert() {
        rule.setContent { TwoForms(stops = false) }
        rule.onAllNodesWithText(string(R.string.payabli_payin_field_account_holder))[0].performTextInput(HOLDER)
        rule.waitForIdle()

        assertEquals(1, rule.onAllNodesWithText(HOLDER).fetchSemanticsNodes().size)
        rule.onAllNodesWithText(string(R.string.payabli_payin_field_account_holder))[1].assertIsNotEnabled()
        rule.onAllNodesWithText(string(R.string.payabli_payin_submit_capture))[1].assertIsNotEnabled()
        assertEquals(
            HOLDER,
            flow.draft.typed.values
                .firstOrNull { it == HOLDER },
        )
        assertTrue("the inert form told the host $told", told.isEmpty())
    }

    @Test
    fun theSecondFormTakesTheFlowOnceTheFirstLeaves() {
        rule.setContent { TwoForms(stops = false) }
        rule.onAllNodesWithText(string(R.string.payabli_payin_field_account_holder))[0].performTextInput(HOLDER)
        rule.waitForIdle()

        rule.runOnIdle { firstOnScreen = false }
        rule.waitForIdle()

        rule.onNodeWithText(HOLDER).assertExists()
        rule.onNodeWithText(string(R.string.payabli_payin_field_routing_number)).assertIsEnabled()
    }

    @Test
    fun aFormReplacedByAnotherOverTheSameFlowInOneFrameIsNoSecondForm() {
        // A host switching layouts: one form leaves and another over the same flow arrives in the same composition.
        var wide by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalPayInFormStopsOnASecondForm provides true) {
                MaterialTheme {
                    if (wide) Column { BankForm() } else BankForm()
                }
            }
        }
        rule.onNodeWithText(string(R.string.payabli_payin_field_account_holder)).performTextInput(HOLDER)
        rule.waitForIdle()

        rule.runOnIdle { wide = false }
        rule.waitForIdle()

        rule.onNodeWithText(HOLDER).assertExists()
        rule.onNodeWithText(string(R.string.payabli_payin_field_routing_number)).assertIsEnabled()
    }

    @Composable
    private fun TwoForms(stops: Boolean) {
        CompositionLocalProvider(LocalPayInFormStopsOnASecondForm provides stops) {
            MaterialTheme {
                Column {
                    if (firstOnScreen) BankForm()
                    BankForm()
                }
            }
        }
    }

    @Composable
    private fun BankForm() {
        PayabliPayInForm(
            payIn = flow,
            operation = PayabliPayInOperation.Capture(testOptions()),
            configuration = bankForm,
            onCompleted = { success: PayInFormSuccess -> told += success },
            onFailed = { failure: PayabliException -> told += failure },
            onMethodChanged = { told += it },
        )
    }

    private val bankForm =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.BankAccount,
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
            labelLayout = PayInLabelLayout.Placeholder,
        )

    private fun string(resource: Int) = rule.activity.getString(resource)

    private companion object {
        const val HOLDER = "Ada Lovelace"
    }
}
