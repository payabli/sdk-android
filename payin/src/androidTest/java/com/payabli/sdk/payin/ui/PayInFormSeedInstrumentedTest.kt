package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.CardBrand
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.TEST_EXPIRY
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the form starts from when a caller hands it values.
 *
 * The seed reaches the same state the payer's typing does, so what it has to prove is that a seeded form is a
 * filled form and not just a decorated one: the boxes show the values, the button is enabled without a
 * keystroke, what a submission carries is what was given, and the payer can still edit it.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormSeedInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var seed by mutableStateOf<PayInFormValues?>(null)

    private val cardSeed =
        PayInFormValues(
            PayInMethodType.Card,
            mapOf(
                PayInField.CardholderName to "Ada Lovelace",
                PayInField.CardNumber to TEST_PAN,
                // The one value a keystroke cannot supply: a dialog picks it, and a seed fills it as the
                // demo's prefill does.
                PayInField.CardExpiration to TEST_EXPIRY,
                PayInField.CardSecurityCode to TEST_SECURITY_CODE,
                PayInField.CardPostalCode to "22039",
            ),
        )

    @Test
    fun aSeededFieldShowsTheValueItWasGiven() {
        showCardForm(cardSeed)

        rule.onNodeWithText("Ada Lovelace").assertExists()
        rule.onNodeWithText("22039").assertExists()
    }

    @Test
    fun aSeededCardNumberIsGroupedAsTypingItWouldBe() {
        // The seed goes through the same state a keystroke writes, so the field's own formatting applies to it.
        showCardForm(cardSeed)

        rule.onNodeWithText("4111 1111 1111 1111").assertExists()
    }

    @Test
    fun aSeededNumberDrawsItsSchemeMark() {
        showCardForm(cardSeed)

        rule.onNodeWithContentDescription(CardBrand.Visa.name).assertExists()
    }

    @Test
    fun aSeededFormSubmitsWithoutAKeystroke() {
        // The button reads the same completeness rules the typing path does. Seeded values that did not reach
        // those rules would leave a full-looking form that cannot be sent.
        var submitted: PayInFormValues? = null
        showCardForm(cardSeed, onSubmit = { submitted = it })

        rule.onNodeWithText(string(R.string.payabli_payin_submit)).performClick()
        rule.waitForIdle()

        val values = requireNotNull(submitted) { "the seeded form refused to submit" }
        assertEquals(TEST_PAN, values[PayInField.CardNumber])
        assertEquals("Ada Lovelace", values[PayInField.CardholderName])
    }

    @Test
    fun typingInOneFieldLeavesTheOtherSeededValuesAlone() {
        showCardForm(cardSeed)

        rule
            .onNode(hasSetTextAction() and hasText(string(R.string.payabli_payin_field_card_postal_code)))
            .performTextInput("-1234")
        rule.waitForIdle()

        rule.onNodeWithText("4111 1111 1111 1111").assertExists()
        rule.onNodeWithText("Ada Lovelace").assertExists()
    }

    @Test
    fun aSeededFieldIsStillThePayersToChange() {
        showCardForm(cardSeed)

        rule
            .onNode(hasSetTextAction() and hasText(string(R.string.payabli_payin_field_cardholder_name)))
            .performTextReplacement("Grace Hopper")
        rule.waitForIdle()

        rule.onNodeWithText("Grace Hopper").assertExists()
        rule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
    }

    @Test
    fun replacingTheSeedStartsTheFormAgainFromTheNewValues() {
        // What a host's second tap on a prefill control does. The values are the remember key, so a new set
        // replaces what is in the boxes instead of merging into it.
        showCardForm(cardSeed)
        rule.onNodeWithText("Ada Lovelace").assertExists()

        rule.runOnIdle {
            seed = PayInFormValues(PayInMethodType.Card, mapOf(PayInField.CardholderName to "Grace Hopper"))
        }
        rule.waitForIdle()

        rule.onNodeWithText("Grace Hopper").assertExists()
        rule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
        rule.onNodeWithText("4111 1111 1111 1111").assertDoesNotExist()
    }

    @Test
    fun noSeedLeavesEveryFieldEmpty() {
        // The other half: a form that showed values with nothing seeded would pass every test above.
        showCardForm(seed = null)

        rule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
        rule.onNodeWithText("4111 1111 1111 1111").assertDoesNotExist()
        CardBrand.entries
            .filter { it != CardBrand.Unknown }
            .forEach { rule.onNodeWithContentDescription(it.name).assertDoesNotExist() }
    }

    private fun showCardForm(
        seed: PayInFormValues?,
        onSubmit: (PayInFormValues) -> Unit = {},
    ) {
        this.seed = seed
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card),
                defaultMethod = PayInMethodType.Card,
                cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS)),
                labelLayout = PayInLabelLayout.Placeholder,
            )
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    configuration = configuration,
                    initialValues = this.seed,
                    onSubmit = {
                        onSubmit(it)
                        true
                    },
                )
            }
        }
    }

    private fun string(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
