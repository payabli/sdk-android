package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInFailureMessagePlacement
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The form's own message for a failure no box on screen shows: its text, its place, and the host's opt-out.
 *
 * Bank rather than card, so every field can be typed: a card expiry is picked from a dialog.
 */
@RunWith(AndroidJUnit4::class)
class PayInFailureNoticeInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Held here rather than inside the composition, which is where a host holds it. */
    private val draft = PayInFormDraft()

    private var submission by mutableStateOf<PayInSubmissionState>(PayInSubmissionState.Idle)

    private var delivered: PayabliException? = null

    private fun failing(type: PayabliErrorType) = failing(PayabliGenericException(type, "the service's own text"))

    private fun failing(
        cause: PayabliException,
        fieldErrors: Map<PayInField, PayInFieldError> = emptyMap(),
    ) = PayInSubmissionState.Failed(cause = cause, fieldErrors = fieldErrors)

    @Test
    fun aDeclinedPaymentSaysSo() {
        failAfterSubmitting(failing(PayabliErrorType.PAYMENT_DECLINED))

        rule.onNodeWithText("The payment was declined. Try another payment method.").assertExists()
    }

    @Test
    fun theMessageIsAPoliteLiveRegion() {
        failAfterSubmitting(failing(PayabliErrorType.PAYMENT_DECLINED))

        rule
            .onNodeWithText("The payment was declined. Try another payment method.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun aPaymentToRetryLaterInvitesARetry() {
        failAfterSubmitting(failing(PayabliErrorType.RATE_LIMITED))

        rule.onNodeWithText("The payment couldn't be completed. Please try again.").assertExists()
    }

    @Test
    fun aPaymentWhoseOutcomeIsUnknownWarnsAgainstPayingTwice() {
        // Wrapping a failure whose own category is retry-later: the wrapper, not the cause, decides.
        failAfterSubmitting(
            failing(PayInException.Unsettled(PayabliGenericException(PayabliErrorType.RATE_LIMITED, "slow"))),
        )

        rule.onNodeWithText("This payment may have gone through. Check before paying again.").assertExists()
        rule.onNodeWithText("The payment couldn't be completed. Please try again.").assertDoesNotExist()
    }

    @Test
    fun aFailureTheSdkDidNotClassifyReadsAsAnUnknownOutcome() {
        failAfterSubmitting(failing(PayabliErrorType.UNKNOWN))

        rule.onNodeWithText("This payment may have gone through. Check before paying again.").assertExists()
    }

    @Test
    fun anyOtherFailureSaysOnlyThatThePaymentDidNotComplete() {
        failAfterSubmitting(failing(PayabliErrorType.TOKEN_EXPIRED))

        rule.onNodeWithText("The payment couldn't be completed.").assertExists()
        rule.onNodeWithText("the service's own text", substring = true).assertDoesNotExist()
    }

    @Test
    fun aFailedSaveShowsTheSaveFormsText() {
        failAfterSubmitting(failing(PayabliErrorType.PAYMENT_DECLINED), submitText = PayInSubmitText.StoreMethod)

        rule.onNodeWithText("This payment method couldn't be saved. Try another one.").assertExists()
        rule.onNodeWithText("The payment was declined. Try another payment method.").assertDoesNotExist()
    }

    @Test
    fun aSaveWhoseOutcomeIsUnknownSaysOnlyThatItWasNotSaved() {
        failAfterSubmitting(failing(PayabliErrorType.UNKNOWN), submitText = PayInSubmitText.StoreMethod)

        rule.onNodeWithText("The payment method couldn't be saved.").assertExists()
    }

    @Test
    fun aFailureMarkingAFieldOnScreenDrawsTheMarkAndNoMessage() {
        failAfterSubmitting(
            failing(
                PayabliGenericException(PayabliErrorType.VALIDATION_ERROR, "refused"),
                mapOf(PayInField.AccountHolder to PayInFieldError.NotAccepted),
            ),
        )

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()
        rule.onNodeWithText("The payment couldn't be completed.").assertDoesNotExist()
    }

    @Test
    fun aFailureWhoseOutcomeIsUnknownDrawsTheMessageBesideItsMarks() {
        failAfterSubmitting(
            failing(
                PayInException.Unsettled(PayabliGenericException(PayabliErrorType.VALIDATION_ERROR, "refused")),
                mapOf(PayInField.AccountHolder to PayInFieldError.NotAccepted),
            ),
        )

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()
        rule.onNodeWithText("This payment may have gone through. Check before paying again.").assertExists()
    }

    @Test
    fun aFailureNamingOnlyFieldsOffScreenDrawsTheMessage() {
        failAfterSubmitting(
            failing(
                PayabliGenericException(PayabliErrorType.VALIDATION_ERROR, "refused"),
                mapOf(PayInField.BillingEmail to PayInFieldError.NotAccepted),
            ),
        )

        rule.onNodeWithText("The payment couldn't be completed.").assertExists()
    }

    @Test
    fun theHostsWordsReplaceTheDefault() {
        failAfterSubmitting(
            failing(PayabliErrorType.PAYMENT_DECLINED),
            labels = PayInFormLabels(declinedMessage = "Your card was declined."),
        )

        rule.onNodeWithText("Your card was declined.").assertExists()
        rule.onNodeWithText("The payment was declined. Try another payment method.").assertDoesNotExist()
    }

    @Test
    fun aBlankReplacementFallsBackToTheDefault() {
        failAfterSubmitting(failing(PayabliErrorType.PAYMENT_DECLINED), labels = PayInFormLabels(declinedMessage = " "))

        rule.onNodeWithText("The payment was declined. Try another payment method.").assertExists()
    }

    @Test
    fun theOptOutDrawsNoMessageAndStillHandsTheFailureOver() {
        val failure = failing(PayabliErrorType.PAYMENT_DECLINED)

        failAfterSubmitting(failure, configuration = bankForm(showsFailureMessage = false))

        rule.onNodeWithText("The payment was declined. Try another payment method.").assertDoesNotExist()
        rule.runOnIdle { assertSame("the failure did not reach the host", failure.cause, delivered) }
    }

    @Test
    fun theOptOutKeepsTheFieldMarks() {
        failAfterSubmitting(
            failing(
                PayabliGenericException(PayabliErrorType.VALIDATION_ERROR, "refused"),
                mapOf(PayInField.AccountHolder to PayInFieldError.NotAccepted),
            ),
            configuration = bankForm(showsFailureMessage = false),
        )

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()
    }

    @Test
    fun byDefaultTheMessageSitsBetweenTheFieldsAndTheSubmitButton() {
        failAfterSubmitting(failing(PayabliErrorType.PAYMENT_DECLINED))

        val message = bounds("The payment was declined. Try another payment method.")
        val lastField = fieldBounds(R.string.payabli_payin_field_account_number)
        val button = rule.onNodeWithText(string(R.string.payabli_payin_submit_capture)).getUnclippedBoundsInRoot()
        assertTrue("the message is not below the fields: $message, $lastField", message.top >= lastField.bottom)
        assertTrue("the message is not above the button: $message, $button", message.bottom <= button.top)
    }

    @Test
    fun atTheTopTheMessageSitsAboveTheFields() {
        failAfterSubmitting(
            failing(PayabliErrorType.PAYMENT_DECLINED),
            configuration = bankForm(placement = PayInFailureMessagePlacement.Top),
        )

        val message = bounds("The payment was declined. Try another payment method.")
        val firstField = fieldBounds(R.string.payabli_payin_field_account_holder)
        assertTrue("the message is not above the fields: $message, $firstField", message.bottom <= firstField.top)
    }

    @Test
    fun theNextSubmissionStartsWithoutTheLastOnesMessage() {
        failAfterSubmitting(failing(PayabliErrorType.PAYMENT_DECLINED))
        rule.onNodeWithText("The payment was declined. Try another payment method.").assertExists()

        fillBank()
        submit()
        rule.runOnIdle { submission = PayInSubmissionState.Submitting }

        rule.onNodeWithText("The payment was declined. Try another payment method.").assertDoesNotExist()
    }

    private fun failAfterSubmitting(
        failure: PayInSubmissionState.Failed,
        configuration: PayInFormConfiguration = bankForm(),
        labels: PayInFormLabels = PayInFormLabels(),
        submitText: PayInSubmitText = PayInSubmitText.Capture,
    ) {
        showForm(configuration, labels, submitText)
        fillBank()
        submit(submitText)
        rule.runOnIdle { submission = failure }
        rule.waitForIdle()
    }

    private fun bankForm(
        placement: PayInFailureMessagePlacement = PayInFailureMessagePlacement.AboveSubmitButton,
        showsFailureMessage: Boolean = true,
    ) = PayInFormConfiguration(
        allowedMethods = listOf(PayInMethodType.BankAccount),
        defaultMethod = PayInMethodType.BankAccount,
        bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
        labelLayout = PayInLabelLayout.Placeholder,
        failureMessagePlacement = placement,
        showsFailureMessage = showsFailureMessage,
    )

    private fun showForm(
        configuration: PayInFormConfiguration,
        labels: PayInFormLabels,
        submitText: PayInSubmitText,
    ) {
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = submission,
                    draft = draft,
                    sent = { true },
                    configuration = configuration,
                    reports = PayInFormReports.None,
                    submitText = submitText,
                    labels = labels,
                    onSubmit = { true },
                    onFailed = { delivered = it },
                )
            }
        }
    }

    private fun fillBank() {
        type(R.string.payabli_payin_field_account_holder, "Ada Lovelace")
        type(R.string.payabli_payin_field_routing_number, TEST_ROUTING)
        type(R.string.payabli_payin_field_account_number, TEST_ACCOUNT)
    }

    private fun submit(submitText: PayInSubmitText = PayInSubmitText.Capture) {
        rule.onNodeWithText(string(submitText.idle)).performClick()
        rule.waitForIdle()
    }

    private fun type(
        labelResource: Int,
        text: String,
    ) {
        rule.onNode(hasSetTextAction() and hasText(string(labelResource))).performTextInput(text)
    }

    private fun bounds(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot()

    private fun fieldBounds(labelResource: Int) =
        rule.onNode(hasSetTextAction() and hasText(string(labelResource))).getUnclippedBoundsInRoot()

    private fun string(resource: Int): String = rule.activity.getString(resource)
}
