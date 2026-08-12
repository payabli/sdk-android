package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInLabelLayout
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
    /**
     * Store an instrument and get a reusable token back. No amount: nothing is being charged.
     *
     * The customer section carries a customer number here and not on the capture form: a stored method belongs
     * to a customer, and the store route refuses one it cannot identify with a `400` that names no field. An
     * integrator sends their own customer's number.
     */
    fun storePaymentMethod(): DemoFormSetup =
        DemoFormSetup(
            configuration =
                PayInFormConfiguration(
                    cardSections = listOf(cardDetails(), customerSection(identified = true)),
                    bankSections = listOf(bankDetails(), customerSection(identified = true)),
                    labelLayout = PayInLabelLayout.Placeholder,
                ),
            labels =
                PayInFormLabels(
                    title = "Save Payment Method",
                    subtitle = "Create a card or ACH token.",
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
                    labelLayout = PayInLabelLayout.Placeholder,
                    summaryValues =
                        mapOf(
                            PayInField.Amount to "$ 1.00",
                            PayInField.ServiceFee to "$ 0.10",
                        ),
                ),
            labels =
                PayInFormLabels(
                    title = "Payment Capture",
                    subtitle = "Submit a card or ACH payment.",
                    submitButton = "Submit payment",
                ),
        )

    /** The instrument sections, which are the SDK's own defaults for both methods. */
    private fun cardDetails() = PayInFormConfiguration.defaultCardSections().single().copy(title = "Card Information")

    private fun bankDetails() = PayInFormConfiguration.defaultBankSections().single().copy(title = "ACH Information")

    /** @param identified adds the customer number the stored-method route requires. */
    private fun customerSection(identified: Boolean = false) =
        PayInFormSection(
            title = "Customer Information",
            fields =
                buildList {
                    add(PayInField.FirstName)
                    add(PayInField.LastName)
                    if (identified) add(PayInField.CustomerNumber)
                    add(PayInField.BillingEmail)
                },
        )

    /**
     * The amount is the caller's, so it is read back and not typed into.
     *
     * An empty box here would invite someone to change a figure the request has already fixed.
     */
    private fun amountSection() =
        PayInFormSection(
            title = "Payment Information",
            fields = listOf(PayInField.Amount, PayInField.ServiceFee),
            style = PayInSectionStyle.Summary,
        )

    /** The method a screen starts on, for anything that needs to name one. */
    val startingMethod: PayInMethodType get() = PayInMethodType.Card
}
