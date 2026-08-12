package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.PayInCardBrand
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scheme's mark, in the field, as the digits arrive.
 *
 * `PayInCardBrandTest` covers which scheme a number names. What it cannot cover is any of this: that the badge
 * is drawn at all, that it swaps when the number changes, that it takes the room the box says, and that it
 * names the scheme to a screen reader. All four are behavior of a composition.
 */
@RunWith(AndroidJUnit4::class)
class PayInBrandBadgeInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aVisaNumberDrawsTheVisaMark() {
        showCardForm()

        typeNumber("4111111111111111")

        rule.onNodeWithContentDescription(PayInCardBrand.Visa.display).assertExists()
    }

    @Test
    fun everySchemeWithArtworkDrawsItsOwnMark() {
        showCardForm()

        mapOf(
            "4111111111111111" to PayInCardBrand.Visa,
            "5555555555554444" to PayInCardBrand.Mastercard,
            "378282246310005" to PayInCardBrand.AmericanExpress,
            "6011111111111117" to PayInCardBrand.Discover,
            "30569309025904" to PayInCardBrand.DinersClub,
            "3530111333300000" to PayInCardBrand.Jcb,
        ).forEach { (number, brand) ->
            typeNumber(number)
            rule.onNodeWithContentDescription(brand.display).assertExists()
        }
    }

    @Test
    fun theMarkSwapsWhenTheNumberChanges() {
        showCardForm()
        typeNumber("4111111111111111")
        rule.onNodeWithContentDescription(PayInCardBrand.Visa.display).assertExists()

        typeNumber("5555555555554444")

        rule.onNodeWithContentDescription(PayInCardBrand.Visa.display).assertDoesNotExist()
        rule.onNodeWithContentDescription(PayInCardBrand.Mastercard.display).assertExists()
    }

    @Test
    fun aNumberNamingNoSchemeDrawsNothing() {
        showCardForm()

        typeNumber("9999999999999999")

        PayInCardBrand.entries
            .filter { it != PayInCardBrand.Unknown }
            .forEach { rule.onNodeWithContentDescription(it.display).assertDoesNotExist() }
    }

    @Test
    fun anEmptyFieldDrawsNothing() {
        showCardForm()

        typeNumber("4111111111111111")
        rule.onNodeWithContentDescription(PayInCardBrand.Visa.display).assertExists()
        numberField().performTextClearance()

        rule.onNodeWithContentDescription(PayInCardBrand.Visa.display).assertDoesNotExist()
    }

    @Test
    fun everyMarkTakesTheRoomTheBoxSays() {
        // The tiles come from artwork whose own aspect ratios run from 1:1 to 3:1, and the box is what makes
        // them one size. A mark that scaled to its own artwork would push the number's text aside.
        //
        // The unmerged tree, because the field merges a trailing icon's description into its own node: the
        // merged node is the whole text field, and measuring it says nothing about the mark.
        showCardForm()

        mapOf(
            "4111111111111111" to PayInCardBrand.Visa,
            "378282246310005" to PayInCardBrand.AmericanExpress,
            "3530111333300000" to PayInCardBrand.Jcb,
        ).forEach { (number, brand) ->
            typeNumber(number)
            rule
                .onNodeWithContentDescription(brand.display, useUnmergedTree = true)
                .assertWidthIsEqualTo(30.dp)
                .assertHeightIsEqualTo(20.dp)
        }
    }

    @Test
    fun aSchemeWithNoArtworkNamesItselfInstead() {
        // UnionPay has no mark in this SDK, and the web surfaces show a generic glyph for it. The badge falls
        // back to the scheme's name, so a payer still sees that the form recognized the card.
        showCardForm()

        typeNumber("6212345678901232")

        rule.onNodeWithText(PayInCardBrand.UnionPay.display).assertExists()
    }

    private fun typeNumber(number: String) {
        numberField().performTextClearance()
        numberField().performTextInput(number)
        rule.waitForIdle()
    }

    private fun numberField() =
        rule.onNode(hasSetTextAction() and hasText(string(R.string.payabli_payin_field_card_number)))

    private fun showCardForm() {
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card),
                defaultMethod = PayInMethodType.Card,
                cardSections = listOf(PayInFormSection(fields = listOf(PayInField.CardNumber))),
                labelLayout = PayInLabelLayout.Placeholder,
            )
        rule.setContent {
            MaterialTheme {
                PayInFormContent(submission = PayInSubmissionState.Idle, configuration = configuration)
            }
        }
    }

    private fun string(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
