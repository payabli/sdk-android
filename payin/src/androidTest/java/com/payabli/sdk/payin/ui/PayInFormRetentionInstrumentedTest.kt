package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.TEST_EXPIRY
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the payer typed is still there after the form's composition ends.
 *
 * Two events end it and neither is unusual: a configuration change, and the destination leaving the back stack
 * while another screen is on top. The values are held by the draft the host's flow owns, so what these tests
 * separate is the draft surviving from the composition surviving.
 *
 * The last one is the other half. Retention that reached saved instance state would pass everything above and
 * put a card number somewhere the system can write to disk, so a form whose draft is new opens empty however the
 * composition was restored.
 */
@RunWith(AndroidJUnit4::class)
class PayInFormRetentionInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Replaceable, so a test can hand the form the new draft a rebuilt flow would bring. */
    private var draft by mutableStateOf(PayInFormDraft())

    private var onScreen by mutableStateOf(true)

    /** The expiry is picked from a dialog, so it is seeded rather than typed. */
    private val seed = PayInFormValues(PayInMethodType.Card, mapOf(PayInField.CardExpiration to TEST_EXPIRY))

    @Test
    fun whatThePayerTypedSurvivesARecreation() {
        val restorer = StateRestorationTester(rule)
        restorer.setContent { Form() }
        fillIn()

        restorer.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("Ada Lovelace").assertExists()
        rule.onNodeWithText(GROUPED_PAN).assertExists()
    }

    @Test
    fun whatThePayerTypedSurvivesTheFormLeavingTheComposition() {
        // A tab switch, and a return from a screen pushed on top of this one. The Activity is never recreated,
        // so nothing is saved or restored and only the draft carries the values across.
        rule.setContent { Form() }
        fillIn()

        rule.runOnIdle { onScreen = false }
        rule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
        rule.runOnIdle { onScreen = true }

        rule.onNodeWithText("Ada Lovelace").assertExists()
        rule.onNodeWithText(GROUPED_PAN).assertExists()
    }

    @Test
    fun aFormWhoseDraftIsNewOpensEmpty() {
        // Process death: the host's flow goes with the process and the one built afterwards brings a draft that
        // has nothing in it.
        rule.setContent { Form() }
        fillIn()

        rule.runOnIdle { draft = PayInFormDraft() }

        rule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
        rule.onNodeWithText(GROUPED_PAN).assertDoesNotExist()
    }

    @Test
    fun aNewDraftIsStillSeededFromTheValuesTheCallerGave() {
        // The other half of the test above: an empty form and a form that lost its seed look the same from the
        // card fields alone.
        rule.setContent { Form() }
        fillIn()

        rule.runOnIdle { draft = PayInFormDraft() }

        rule.onNodeWithText(TEST_EXPIRY).assertExists()
    }

    @Test
    fun nothingTypedComesBackFromSavedInstanceState() {
        val restorer = StateRestorationTester(rule)
        restorer.setContent { Form() }
        fillIn()

        // The draft is replaced first, so the restore is the only thing left that could bring a value back.
        rule.runOnIdle { draft = PayInFormDraft() }
        restorer.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
        rule.onNodeWithText(GROUPED_PAN).assertDoesNotExist()
    }

    private fun fillIn() {
        type(R.string.payabli_payin_field_cardholder_name, "Ada Lovelace")
        type(R.string.payabli_payin_field_card_number, TEST_PAN)
    }

    private val configuration =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card),
            defaultMethod = PayInMethodType.Card,
            cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS)),
            labelLayout = PayInLabelLayout.Placeholder,
        )

    @Composable
    private fun Form() {
        MaterialTheme {
            if (onScreen) {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = configuration,
                    reports = PayInFormReports.None,
                    initialValues = seed,
                )
            }
        }
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

    private companion object {
        /** The card number as the field writes it, which is what a query off the screen has to match. */
        const val GROUPED_PAN = "4111 1111 1111 1111"
    }
}
