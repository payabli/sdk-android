package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle

/**
 * What this app hands the SDK's form, for one operation.
 *
 * Two values because the SDK separates them: [PayInFormConfiguration] is what to collect,
 * [PayInFormLabels] is what to call it. A caller usually wants one and not the other.
 */
data class DemoFormSetup(
    val configuration: PayInFormConfiguration,
    val labels: PayInFormLabels,
)

/**
 * The two forms this app shows.
 *
 * Written the way an integrator would write them: the fields and sections they want, the wording
 * they want, and nothing about appearance. The form takes its colours and type from this app's
 * theme with nothing passed, which is the property the Setup screen's readout is checking.
 */
object DemoForms {
    /** Store an instrument and get a reusable token back. No amount: nothing is being charged. */
    fun storePaymentMethod(): DemoFormSetup =
        DemoFormSetup(
            configuration =
                PayInFormConfiguration(
                    cardSections = listOf(cardDetails(), customerSection()),
                    bankSections = listOf(bankDetails(), customerSection()),
                ),
            labels =
                PayInFormLabels(
                    title = "Save a payment method",
                    subtitle = "Store a card or bank account and get a reusable token back.",
                    submitButton = "Save payment method",
                ),
        )

    /** Take a payment now. The same instrument fields, plus what is being charged. */
    fun capture(): DemoFormSetup =
        DemoFormSetup(
            configuration =
                PayInFormConfiguration(
                    cardSections = listOf(cardDetails(), customerSection(), amountSection()),
                    bankSections = listOf(bankDetails(), customerSection(), amountSection()),
                    summaryValues =
                        mapOf(
                            PayInField.Amount to "$ 1.00",
                            PayInField.ServiceFee to "$ 0.10",
                        ),
                ),
            labels =
                PayInFormLabels(
                    title = "Take a payment",
                    subtitle = "Charge a card or bank account.",
                    submitButton = "Submit payment",
                ),
        )

    /** The instrument sections, which are the SDK's own defaults for both methods. */
    private fun cardDetails() = PayInFormConfiguration.defaultCardSections().single().copy(title = "Card")

    private fun bankDetails() = PayInFormConfiguration.defaultBankSections().single().copy(title = "Bank account")

    private fun customerSection() =
        PayInFormSection(
            title = "Customer",
            fields = listOf(PayInField.FirstName, PayInField.LastName, PayInField.BillingEmail),
        )

    /**
     * The amount is the caller's, so it is read back and not typed into.
     *
     * An empty box here would invite someone to change a figure the request has already fixed.
     */
    private fun amountSection() =
        PayInFormSection(
            title = "Payment",
            fields = listOf(PayInField.Amount, PayInField.ServiceFee),
            style = PayInSectionStyle.Summary,
        )

    /** The method a screen starts on, for anything that needs to name one. */
    val startingMethod: PayInMethodType get() = PayInMethodType.Card
}
