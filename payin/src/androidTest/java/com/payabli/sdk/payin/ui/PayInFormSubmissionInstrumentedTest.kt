package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the form does with a submission state, on screen.
 *
 * Needs a real Activity: the clearing, the marking and the values the form reports are all behavior of a
 * composition, and a unit test renders nothing. One question per test.
 *
 * The fields are found by their label, which sits inside the box under [PayInLabelLayout.Placeholder]: that is
 * the layout whose label Material puts in the field's own semantics, so a query finds the node that takes text.
 *
 * **The sections here leave out the expiry and the account type**, which are the two fields a payer picks from a
 * dialog and a menu instead of typing. Every remaining field can be filled by typing, so the submit button
 * enables and these tests go through the real tap. The set a success empties is pinned by
 * `PayInSensitiveFieldsTest`; the wiring to it is pinned here.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormSubmissionInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var submission by mutableStateOf<PayInSubmissionState>(PayInSubmissionState.Idle)

    /** The last values the form reported, which is the copy a host holds. */
    private var reported: PayInFormValues? = null

    private val succeeded = PayInSubmissionState.Succeeded.Payment(PayInResult("A0000", null))

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

        val values = requireNotNull(reported) { "the form reported nothing" }
        assertEquals("the card number was kept", "", values[PayInField.CardNumber])
        assertEquals("the security code was kept", "", values[PayInField.CardSecurityCode])
        assertEquals("Ada Lovelace", values[PayInField.CardholderName])
        assertEquals("22039", values[PayInField.CardPostalCode])
    }

    @Test
    fun aBankSuccessEmptiesTheAccountAndTheRoutingNumber() {
        showBankForm()
        type(R.string.payabli_payin_field_account_holder, "Ada Lovelace")
        type(R.string.payabli_payin_field_routing_number, TEST_ROUTING)
        type(R.string.payabli_payin_field_account_number, TEST_ACCOUNT)
        submit()

        rule.runOnIdle { submission = succeeded }
        rule.waitForIdle()

        val values = requireNotNull(reported) { "the form reported nothing" }
        assertEquals("the account number was kept", "", values[PayInField.AccountNumber])
        assertEquals("the routing number was kept", "", values[PayInField.RoutingNumber])
        assertEquals("Ada Lovelace", values[PayInField.AccountHolder])
    }

    @Test
    fun aFailureKeepsEveryFieldTheWayThePayerLeftIt() {
        showCardForm()
        fillCard()
        submit()

        rule.runOnIdle { submission = refusing(PayInField.CardNumber) }
        rule.waitForIdle()

        val values = requireNotNull(reported) { "the form reported nothing" }
        assertEquals(TEST_PAN, values[PayInField.CardNumber])
        assertEquals(TEST_SECURITY_CODE, values[PayInField.CardSecurityCode])
        assertEquals("Ada Lovelace", values[PayInField.CardholderName])
        assertEquals("22039", values[PayInField.CardPostalCode])
    }

    @Test
    fun aSuccessThisFormDidNotSubmitLeavesTheFieldsAlone() {
        showCardForm()
        fillCard()

        // No tap. The state belongs to the host, so this is a success from somewhere else: another screen's
        // holder, or the payment before this one.
        rule.runOnIdle { submission = succeeded }
        rule.waitForIdle()

        val values = requireNotNull(reported) { "the form reported nothing" }
        assertEquals("a card number the payer was still typing was emptied", TEST_PAN, values[PayInField.CardNumber])
        assertEquals(TEST_SECURITY_CODE, values[PayInField.CardSecurityCode])
    }

    @Test
    fun aRefusalIsShownOnTheFieldItNamed() {
        showCardForm()
        // A number that passes every local rule, so the only message the field can show is the refusal.
        type(R.string.payabli_payin_field_card_number, TEST_PAN)

        rule.runOnIdle { submission = refusing(PayInField.CardNumber) }

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()
    }

    @Test
    fun editingTheRefusedFieldTakesTheMessageAway() {
        showCardForm()
        type(R.string.payabli_payin_field_card_number, TEST_PAN)
        rule.runOnIdle { submission = refusing(PayInField.CardholderName) }
        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertExists()

        type(R.string.payabli_payin_field_cardholder_name, "Ada Lovelace")

        rule.onNodeWithText(string(R.string.payabli_payin_error_not_accepted)).assertDoesNotExist()
    }

    private fun fillCard() {
        type(R.string.payabli_payin_field_cardholder_name, "Ada Lovelace")
        type(R.string.payabli_payin_field_card_number, TEST_PAN)
        type(R.string.payabli_payin_field_card_security_code, TEST_SECURITY_CODE)
        type(R.string.payabli_payin_field_card_postal_code, "22039")
    }

    private fun showCardForm() =
        showForm(
            PayInMethodType.Card,
            PayInFormSection(
                fields =
                    listOf(
                        PayInField.CardholderName,
                        PayInField.CardNumber,
                        PayInField.CardSecurityCode,
                        PayInField.CardPostalCode,
                    ),
            ),
        )

    private fun showBankForm() =
        showForm(
            PayInMethodType.BankAccount,
            PayInFormSection(
                fields =
                    listOf(
                        PayInField.AccountHolder,
                        PayInField.RoutingNumber,
                        PayInField.AccountNumber,
                    ),
            ),
        )

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
                    configuration = configuration,
                    onSubmit = { true },
                    onValuesChanged = { reported = it },
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
