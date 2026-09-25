package com.payabli.sdk.payin.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.telemetry.PayInFormReports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.Locale

/** The summary rows draw the operation's own figures, and only the ones that are not zero. */
@RunWith(AndroidJUnit4::class)
class PayInSummaryRowsInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val configuration =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card),
            cardSections =
                listOf(
                    PayInFormSection(fields = CARD_INSTRUMENT_FIELDS),
                    PayInFormSection(
                        fields = listOf(PayInField.Amount, PayInField.ServiceFee, PayInField.SurchargeFee),
                        style = PayInSectionStyle.Summary,
                    ),
                ),
        )

    @Test
    fun eachAmountThatIsNotZeroIsDrawnWithItsFigure() {
        show(PayInPaymentDetails(BigDecimal("12.34"), serviceFee = BigDecimal("0.10"), currency = "USD"))

        rule.onNodeWithText(figure("12.34", "USD")).assertExists()
        rule.onNodeWithText(figure("0.10", "USD")).assertExists()
        rule.onNodeWithText(string(R.string.payabli_payin_field_surcharge_fee)).assertDoesNotExist()
    }

    @Test
    fun aRowIsReadAsItsLabelAndFigureTogether() {
        show(PayInPaymentDetails(BigDecimal("12.34"), currency = "USD"))

        // One node carries both, which is what a screen reader announces as a row.
        rule
            .onNode(hasText(string(R.string.payabli_payin_field_amount)) and hasText(figure("12.34", "USD")))
            .assertExists()
    }

    @Test
    fun aLongLabelLeavesAGapBeforeTheFigure() {
        val label = "An amount label long enough to take every bit of the row it is given"
        val draft = PayInFormDraft()
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = configuration,
                    reports = PayInFormReports.None,
                    labels = PayInFormLabels(fieldLabels = mapOf(PayInField.Amount to label)),
                    amounts = PayInPaymentDetails(BigDecimal("12.34"), currency = "USD"),
                )
            }
        }

        val labelEnd = rule.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot().right
        val figureStart = rule.onNodeWithText(figure("12.34", "USD"), useUnmergedTree = true).getBoundsInRoot().left
        assertTrue("the label runs to $labelEnd and the figure starts at $figureStart", figureStart - labelEnd >= 12.dp)
    }

    @Test
    fun aFormWithNoSummarySectionStillShowsTheCharge() {
        show(
            PayInPaymentDetails(BigDecimal("12.34"), serviceFee = BigDecimal("0.10"), currency = "USD"),
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card),
                cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS)),
            ),
        )

        rule.onNodeWithText(string(R.string.payabli_payin_section_summary)).assertExists()
        rule.onNodeWithText(figure("12.34", "USD")).assertExists()
        rule.onNodeWithText(figure("0.10", "USD")).assertExists()
    }

    @Test
    fun anOperationThatChargesNothingDrawsNoSummary() {
        show(null)

        rule.onNodeWithText(string(R.string.payabli_payin_section_summary)).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.payabli_payin_field_amount)).assertDoesNotExist()
    }

    @Test
    fun aRepricedOperationRedrawsTheFigure() {
        val amounts = mutableStateOf(PayInPaymentDetails(BigDecimal("12.34"), currency = "USD"))
        show { amounts.value }

        rule.runOnUiThread { amounts.value = PayInPaymentDetails(BigDecimal("15.00"), currency = "USD") }

        rule.onNodeWithText(figure("15.00", "USD")).assertExists()
        rule.onNodeWithText(figure("12.34", "USD")).assertDoesNotExist()
    }

    @Test
    fun thePlatformFormatterWritesWhatTheJvmOneDoes() {
        // The unit tier formats with the JDK's locale data and a device formats with its own.
        assertEquals("$1,234.56", formatAmount(BigDecimal("1234.56"), "USD", Locale.US))
        assertEquals("1.234,56 €", formatAmount(BigDecimal("1234.56"), "EUR", Locale.GERMANY))
        assertEquals("1,234.56", formatAmount(BigDecimal("1234.56"), null, Locale.US))
    }

    private fun show(
        amounts: PayInPaymentDetails?,
        form: PayInFormConfiguration = configuration,
    ) = show(form) { amounts }

    private fun show(
        form: PayInFormConfiguration = configuration,
        amounts: () -> PayInPaymentDetails?,
    ) {
        val draft = PayInFormDraft()
        rule.setContent {
            MaterialTheme {
                PayInFormContent(
                    submission = PayInSubmissionState.Idle,
                    draft = draft,
                    configuration = form,
                    reports = PayInFormReports.None,
                    amounts = amounts(),
                )
            }
        }
    }

    private fun figure(
        amount: String,
        currency: String,
    ): String = formatAmount(BigDecimal(amount), currency, Locale.getDefault())

    private fun string(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
