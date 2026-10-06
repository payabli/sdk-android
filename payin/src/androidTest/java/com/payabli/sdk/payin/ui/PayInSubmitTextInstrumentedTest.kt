package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The button reads the operation's verb, and its busy form while a submission runs. */
@RunWith(AndroidJUnit4::class)
class PayInSubmitTextInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var submitText by mutableStateOf(PayInSubmitText.Capture)
    private var labels by mutableStateOf(PayInFormLabels())
    private var submission by mutableStateOf<PayInSubmissionState>(PayInSubmissionState.Idle)

    @Test
    fun eachOperationReadsItsOwnVerbAndItsBusyForm() {
        showForm()

        mapOf(
            PayInSubmitText.Capture to ("Pay" to "Paying…"),
            PayInSubmitText.Authorize to ("Authorize" to "Authorizing…"),
            PayInSubmitText.StoreMethod to ("Save" to "Saving…"),
        ).forEach { (text, wording) ->
            rule.runOnIdle {
                submitText = text
                submission = PayInSubmissionState.Idle
            }
            rule.onNodeWithText(wording.first).assertExists()

            rule.runOnIdle { submission = PayInSubmissionState.Submitting }
            rule.onNodeWithText(wording.second).assertExists()
            rule.onNodeWithText(wording.first).assertDoesNotExist()
        }
    }

    @Test
    fun aHostsWordingReplacesTheVerbAndNotTheBusyForm() {
        labels = PayInFormLabels(submitButton = "Place order")
        showForm()

        rule.onNodeWithText("Place order").assertExists()
        rule.onNodeWithText("Pay").assertDoesNotExist()

        rule.runOnIdle { submission = PayInSubmissionState.Submitting }
        rule.onNodeWithText("Paying…").assertExists()
    }

    @Test
    fun aBlankWordingFromTheHostFallsBackToTheVerb() {
        labels = PayInFormLabels(submitButton = "  ")
        showForm()

        rule.onNodeWithText("Pay").assertExists()
    }

    private fun showForm() {
        val draft = PayInFormDraft()
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = submission,
                    draft = draft,
                    configuration = PayInFormConfiguration(),
                    reports = PayInFormReports.None,
                    submitText = submitText,
                    labels = labels,
                )
            }
        }
    }
}
