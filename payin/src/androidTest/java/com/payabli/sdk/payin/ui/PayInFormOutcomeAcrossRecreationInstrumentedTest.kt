package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.TEST_EXPIRY
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The outcome of a submission this form sent still reaches the callbacks after the Activity is recreated.
 *
 * The flow is the host's and survives a rotation; what the form has to keep is the knowledge that the
 * submission in flight is *its own*, so a terminal state arriving afterwards is delivered rather than read as
 * an outcome the host was already holding. `PayabliPayInForm`'s KDoc promises exactly that.
 *
 * `PayInSubmissionRetentionInstrumentedTest` covers the flow across a recreation and drives `start` directly,
 * so it never exercises this. `PayInFormRetentionInstrumentedTest` covers the values beside the flag.
 *
 * [StateRestorationTester] restores saved state the way a configuration change does, without needing the
 * Activity to hold the composition under test.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormOutcomeAcrossRecreationInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Held here rather than inside the composition, which is where a host holds it. */
    private val draft = PayInFormDraft()

    private var submission by mutableStateOf<PayInSubmissionState>(PayInSubmissionState.Idle)

    private val completed = mutableListOf<PayInSubmissionState.Succeeded>()
    private val failed = mutableListOf<PayInSubmissionState.Failed>()

    @Test
    fun aSuccessAfterRecreationStillReachesOnCompleted() {
        val restorer = showFormWithRestoration()
        fillAndSubmit()

        rule.runOnIdle { submission = PayInSubmissionState.Submitting }
        restorer.emulateSavedInstanceStateRestore()
        rule.runOnIdle { submission = PayInSubmissionState.Succeeded.Payment(PayInResult("A0000", null)) }
        rule.waitForIdle()

        assertEquals("the success did not reach the form's caller", 1, completed.size)
    }

    @Test
    fun aFailureAfterRecreationStillReachesOnFailed() {
        val restorer = showFormWithRestoration()
        fillAndSubmit()

        rule.runOnIdle { submission = PayInSubmissionState.Submitting }
        restorer.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            submission =
                PayInSubmissionState.Failed(
                    PayInException.Refused(PayInFailure("D1001", "Refused", null, null, 200)),
                )
        }
        rule.waitForIdle()

        assertEquals("the refusal did not reach the form's caller", 1, failed.size)
    }

    @Test
    fun anOutcomeThisFormNeverSubmittedIsStillIgnoredAfterRecreation() {
        // The other half of the flag. Restoring it as true regardless would deliver another screen's outcome,
        // which is what it exists to prevent.
        val restorer = showFormWithRestoration()

        restorer.emulateSavedInstanceStateRestore()
        rule.runOnIdle { submission = PayInSubmissionState.Succeeded.Payment(PayInResult("A0000", null)) }
        rule.waitForIdle()

        assertEquals("an outcome this form did not send was delivered", 0, completed.size)
    }

    @Test
    fun aRestoredFlagDoesNotOutliveTheFlowThatCarriedTheSubmission() {
        // Process death, not a rotation: the host's flow goes with the process and the one built afterwards
        // starts idle. The flag is saved either way, so a form restoring it as true would take the next
        // outcome on the new flow as its own, and the screen that really submitted would get nothing.
        val restorer = showFormWithRestoration()
        fillAndSubmit()
        rule.runOnIdle { submission = PayInSubmissionState.Submitting }

        restorer.emulateSavedInstanceStateRestore()
        rule.runOnIdle { submission = PayInSubmissionState.Idle }
        rule.runOnIdle { submission = PayInSubmissionState.Succeeded.Payment(PayInResult("A0000", null)) }
        rule.waitForIdle()

        assertEquals("another screen's outcome was delivered here", 0, completed.size)
    }

    private fun fillAndSubmit() {
        // The expiry is picked from a dialog and cannot be typed, and this is not a test of the picker, so it
        // goes into the draft directly: the same state a pick writes, on the test's own object.
        rule.runOnIdle { draft.enter(PayInField.CardExpiration, TEST_EXPIRY) }
        type(R.string.payabli_payin_field_cardholder_name, "Ada Lovelace")
        type(R.string.payabli_payin_field_card_number, TEST_PAN)
        type(R.string.payabli_payin_field_card_security_code, TEST_SECURITY_CODE)
        type(R.string.payabli_payin_field_card_postal_code, "22039")
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).performClick()
        rule.waitForIdle()
    }

    private fun showFormWithRestoration(): StateRestorationTester {
        val restorer = StateRestorationTester(rule)
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card),
                defaultMethod = PayInMethodType.Card,
                cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS)),
                labelLayout = PayInLabelLayout.Placeholder,
            )
        restorer.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = submission,
                    draft = draft,
                    configuration = configuration,
                    reports = PayInFormReports.None,
                    onSubmit = { true },
                    onCompleted = { completed += it },
                    onFailed = { failed += it },
                )
            }
        }
        return restorer
    }

    private fun type(
        labelResource: Int,
        text: String,
    ) {
        rule.onNode(hasSetTextAction() and hasText(string(labelResource))).performTextInput(text)
        rule.waitForIdle()
    }

    private fun string(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
