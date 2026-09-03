package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the form does with a submission state, on screen.
 *
 * Needs a real Activity: the clearing and the marking are both behavior of a composition, and a unit test
 * renders nothing. One question per test.
 *
 * The fields are found by their label, which sits inside the box under [PayInLabelLayout.Placeholder]: that is
 * the layout whose label Material puts in the field's own semantics, so a query finds the node that takes text.
 *
 * **The expiry is written into the draft and the account type is left out**, because those are the two values a
 * payer picks from a dialog and a menu instead of typing. Everything else is typed, so the submit button
 * enables and these tests go through the real tap. What each box holds is read from the screen, which is the
 * only copy there is now that the form reports no values. The cleared set itself is pinned by
 * `PayInSensitiveFieldsTest`.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormSubmissionInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Held here rather than inside the composition, which is where a host holds it. */
    private val draft = PayInFormDraft()

    private var submission by mutableStateOf<PayInSubmissionState>(PayInSubmissionState.Idle)

    private val succeeded =
        PayInSubmissionState.Succeeded.Payment(
            PayInResult("A0000", reason = null, explanation = null, action = null, transaction = null),
        )

    /** The test card as the field draws it, which is how a query finds what a box is holding. */
    private val groupedPan = "4111 1111 1111 1111"

    private fun refusing(field: PayInField): PayInSubmissionState.Failed =
        PayInSubmissionState.Failed(
            cause = PayInException.Refused(PayInFailure("D1001", "Refused", null, null, 200)),
            fieldErrors = mapOf(field to PayInFieldError.NotAccepted),
        )

    @Test
    fun aSuccessEmptiesTheInstrumentAndKeepsTheRest() {
        showCardForm()
        fillCard()
        submit()

        rule.runOnIdle { submission = succeeded }
        rule.waitForIdle()

        rule.onNodeWithText(groupedPan).assertDoesNotExist()
        rule.onNodeWithText("Ada Lovelace").assertExists()
        rule.onNodeWithText("22039").assertExists()
        // The instrument is gone, so the form has nothing to send until the payer enters one.
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).assertIsNotEnabled()
    }

    @Test
    fun aRefusalEmptiesTheInstrumentAsAnApprovalDoes() {
        // The instrument was submitted either way, and a security code has no reason to outlive the attempt
        // it authenticated.
        showCardForm()
        fillCard()
        submit()

        rule.runOnIdle { submission = refusing(PayInField.CardNumber) }
        rule.waitForIdle()

        rule.onNodeWithText(groupedPan).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).assertIsNotEnabled()
    }

    @Test
    fun aBankSuccessEmptiesTheAccountAndTheRoutingNumber() {
        showBankForm()
        fillBank()
        type(R.string.payabli_payin_field_first_name, "Ada")
        submit()

        rule.runOnIdle { submission = succeeded }
        rule.waitForIdle()

        rule.onNodeWithText(TEST_ROUTING).assertDoesNotExist()
        rule.onNodeWithText("Ada Lovelace").assertExists()
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).assertIsNotEnabled()
    }

    @Test
    fun aFailureKeepsWhatIdentifiesThePayer() {
        // Everything outside the instrument, so a second attempt is not a second round of typing.
        showCardForm()
        fillCard()
        submit()

        rule.runOnIdle { submission = refusing(PayInField.CardNumber) }
        rule.waitForIdle()

        rule.onNodeWithText("Ada Lovelace").assertExists()
        rule.onNodeWithText("22039").assertExists()
    }

    @Test
    fun aSuccessThisFormDidNotSubmitLeavesTheFieldsAlone() {
        showCardForm()
        fillCard()

        // No tap. The state belongs to the host, so this is a success from somewhere else: another screen's
        // holder, or the payment before this one.
        rule.runOnIdle { submission = succeeded }
        rule.waitForIdle()

        rule.onNodeWithText(groupedPan).assertExists()
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).assertIsEnabled()
    }

    @Test
    fun aRefusalIsShownOnTheFieldItNamed() {
        // Through the tap, because a refusal is only this form's to show if this form sent the submission.
        showCardForm()
        fillCard()
        submit()

        rule.runOnIdle { submission = refusing(PayInField.CardNumber) }

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()
    }

    @Test
    fun editingTheRefusedFieldTakesTheMessageAway() {
        showCardForm()
        fillCard()
        submit()
        rule.runOnIdle { submission = refusing(PayInField.CardholderName) }
        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()

        type(R.string.payabli_payin_field_cardholder_name, " Byron")

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertDoesNotExist()
    }

    @Test
    fun aRefusedValueCannotBeSentAgainUntilItIsEdited() {
        // Bank, because every field it needs can be typed: after an outcome the instrument is empty and a
        // card expiry can only be picked from a dialog.
        //
        // The refusal names a field outside the instrument, so re-entering the account leaves the refused
        // value standing. Submit stays off until that box changes, or the same rejected value goes out
        // again under the message that says it was rejected.
        showBankForm()
        fillBank()
        type(R.string.payabli_payin_field_first_name, "Ada")
        submit()
        rule.runOnIdle { submission = refusing(PayInField.FirstName) }
        fillBank()

        rule.onNodeWithText(string(R.string.payabli_payin_submit)).assertIsNotEnabled()

        type(R.string.payabli_payin_field_first_name, "h")

        rule.onNodeWithText(string(R.string.payabli_payin_submit)).assertIsEnabled()
    }

    @Test
    fun aRefusalOfValuesThisFormNeverSentMarksNothing() {
        // Two forms on one flow, which a host mounting a sheet over the inline form has. The field the service
        // named is a field in the *other* form's values, and marking it here points the payer at a box whose
        // value the service never saw.
        showCardForm()
        type(R.string.payabli_payin_field_card_number, TEST_PAN)

        rule.runOnIdle { submission = refusing(PayInField.CardNumber) }

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertDoesNotExist()
    }

    private fun fillCard() {
        type(R.string.payabli_payin_field_cardholder_name, "Ada Lovelace")
        type(R.string.payabli_payin_field_card_number, TEST_PAN)
        type(R.string.payabli_payin_field_card_security_code, TEST_SECURITY_CODE)
        type(R.string.payabli_payin_field_card_postal_code, "22039")
    }

    private fun fillBank() {
        type(R.string.payabli_payin_field_account_holder, "Ada Lovelace")
        type(R.string.payabli_payin_field_routing_number, TEST_ROUTING)
        type(R.string.payabli_payin_field_account_number, TEST_ACCOUNT)
    }

    private fun showCardForm() {
        showForm(
            PayInMethodType.Card,
            PayInFormSection(fields = CARD_INSTRUMENT_FIELDS),
        )
        pickTheExpiry()
    }

    private fun showBankForm() =
        showForm(
            PayInMethodType.BankAccount,
            PayInFormSection(fields = BANK_INSTRUMENT_FIELDS + PayInField.FirstName),
        )

    /**
     * The expiry, written into the draft rather than picked from its dialog.
     *
     * A payer picks it and cannot type it, and none of these tests is about the picker: they need a card form
     * complete enough to submit. The draft is the test's own object and this is the state a pick writes.
     */
    private fun pickTheExpiry() = rule.runOnIdle { draft.enter(PayInField.CardExpiration, TEST_EXPIRY) }

    private fun showForm(
        method: PayInMethodType,
        section: PayInFormSection,
    ) {
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(method),
                defaultMethod = method,
                cardSections = listOf(section),
                bankSections = listOf(section),
                labelLayout = PayInLabelLayout.Placeholder,
            )
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = submission,
                    draft = draft,
                    configuration = configuration,
                    reports = PayInFormReports.None,
                    onSubmit = { true },
                )
            }
        }
    }

    private fun submit() {
        rule.onNodeWithText(string(R.string.payabli_payin_submit)).performClick()
        rule.waitForIdle()
    }

    private fun type(
        labelResource: Int,
        text: String,
    ) {
        rule.onNode(hasSetTextAction() and hasText(string(labelResource))).performTextInput(text)
    }

    private fun string(resource: Int): String = rule.activity.getString(resource)
}
