package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.BANK_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormatting
import com.payabli.sdk.payin.form.PayInLabelLayout
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The control that uncovers a masked field, and puts it back.
 *
 * Both directions, and the second is the point: hiding again is the half a payer uses to put a number away
 * with somebody beside them, and it swaps the icon and the wording a screen reader announces. Asserted on the
 * content description rather than the icon, because that description is the part a payer who cannot see the
 * glyph depends on.
 *
 * Also covers the case the file warns about at the modifier: with masking off there is no control at all,
 * because a reveal over an already-clear field toggles nothing.
 */
@RunWith(AndroidJUnit4::class)
class PayInRevealControlInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val draft = PayInFormDraft()

    @Test
    fun aMaskedFieldOffersToRevealIt() {
        showBankForm(masksAccountNumber = true)

        rule.onNodeWithContentDescription(revealLabel(), substring = true).assertExists()
    }

    /** Tapping once uncovers it, and the control now offers to hide it rather than to reveal it again. */
    @Test
    fun revealingSwapsTheControlToHide() {
        showBankForm(masksAccountNumber = true)

        rule.onNodeWithContentDescription(revealLabel(), substring = true).performClick()

        rule.onNodeWithContentDescription(hideLabel(), substring = true).assertExists()
        rule.onNodeWithContentDescription(revealLabel(), substring = true).assertDoesNotExist()
    }

    /** And back, which is the state nothing reached before. */
    @Test
    fun hidingSwapsItBack() {
        showBankForm(masksAccountNumber = true)

        rule.onNodeWithContentDescription(revealLabel(), substring = true).performClick()
        rule.onNodeWithContentDescription(hideLabel(), substring = true).performClick()

        rule.onNodeWithContentDescription(revealLabel(), substring = true).assertExists()
        rule.onNodeWithContentDescription(hideLabel(), substring = true).assertDoesNotExist()
    }

    /** What the payer typed reads back in clear once revealed, which is what the control is for. */
    @Test
    fun theDigitsReadBackOnceRevealed() {
        showBankForm(masksAccountNumber = true)

        accountNumberField().performTextInput("123456789")
        rule.waitForIdle()

        rule.onNodeWithContentDescription(revealLabel(), substring = true).performClick()

        rule.onNode(hasSetTextAction() and hasText("123456789")).assertExists()
    }

    /**
     * With masking off there is no control, because there is nothing to uncover.
     *
     * The trailing slot is the reveal control's only where a field is masked, and the file says so.
     */
    @Test
    fun anUnmaskedAccountNumberOffersNoControl() {
        showBankForm(masksAccountNumber = false)

        rule.onNodeWithContentDescription(revealLabel(), substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription(hideLabel(), substring = true).assertDoesNotExist()
    }

    /**
     * The control follows the form's enabled state, so a submission in flight does not leave it live.
     *
     * Both states, because the idle half alone passes over a control that ignores `enabled` outright. The
     * submitting half is the one that says anything.
     */
    @Test
    fun theControlIsLiveOnlyWhileTheFormIs() {
        showBankForm(masksAccountNumber = true, submission = PayInSubmissionState.Idle)

        rule.onNodeWithContentDescription(revealLabel(), substring = true).assertIsEnabled()
    }

    @Test
    fun aSubmissionInFlightTakesTheControlDown() {
        showBankForm(masksAccountNumber = true, submission = PayInSubmissionState.Submitting)

        rule.onNodeWithContentDescription(revealLabel(), substring = true).assertIsNotEnabled()
    }

    private fun accountNumberField() =
        rule.onNode(hasSetTextAction() and hasText(string(R.string.payabli_payin_field_account_number)))

    /**
     * The wording takes the field's label, so the description is longer than the bare word.
     *
     * Matched on the stem by substring for that reason, rather than restating the whole formatted string.
     */
    private fun revealLabel() = string(R.string.payabli_payin_reveal).substringBefore(" %")

    private fun hideLabel() = string(R.string.payabli_payin_hide).substringBefore(" %")

    private fun showBankForm(
        masksAccountNumber: Boolean,
        submission: PayInSubmissionState = PayInSubmissionState.Idle,
    ) {
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.BankAccount),
                defaultMethod = PayInMethodType.BankAccount,
                bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS)),
                labelLayout = PayInLabelLayout.Placeholder,
                formatting = PayInFormatting(masksAccountNumber = masksAccountNumber),
            )
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = submission,
                    draft = draft,
                    configuration = configuration,
                    reports = PayInFormReports.None,
                )
            }
        }
    }

    private fun string(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
