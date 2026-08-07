package com.payabli.example.app.payment

/** How a section presents its fields. */
enum class SectionStyle {
    /** Boxes the payer types into. */
    Inputs,

    /**
     * Label and value, read only.
     *
     * The amount on a capture is set by the caller, not by the payer, so showing it as an empty box
     * would invite someone to change a figure the request has already fixed.
     */
    Summary,
}

/** A titled group of fields. */
data class PaymentFormSection(
    val title: String,
    val fields: List<PaymentField>,
    val style: SectionStyle = SectionStyle.Inputs,
)

/**
 * What the payment form should render.
 *
 * This is the part of the seam the app keeps. It describes the form and does not draw it, so the
 * same object can drive the placeholder today and be mapped onto the SDK component's configuration
 * tomorrow, with the two screens unchanged either way.
 *
 * It is also what makes the placeholder worth having: it renders this, so a section in the wrong
 * order or a field in the wrong group is visible now, and not on the day the real form lands.
 */
data class PaymentFormConfiguration(
    val title: String,
    val subtitle: String,
    val submitLabel: String,
    val allowedMethods: List<PaymentMethodType>,
    val defaultMethod: PaymentMethodType,
    val cardSections: List<PaymentFormSection>,
    val bankSections: List<PaymentFormSection>,
    /** Values for the fields in a [SectionStyle.Summary] section, already formatted for display. */
    val summaryValues: Map<PaymentField, String> = emptyMap(),
) {
    fun sectionsFor(method: PaymentMethodType): List<PaymentFormSection> =
        when (method) {
            PaymentMethodType.Card -> cardSections
            PaymentMethodType.BankAccount -> bankSections
        }

    companion object {
        private val customerSection =
            PaymentFormSection(
                title = "Customer",
                fields = listOf(PaymentField.FirstName, PaymentField.LastName, PaymentField.Email),
            )

        /** Store an instrument and get a reusable token back. No amount: nothing is being charged. */
        fun storePaymentMethod(): PaymentFormConfiguration =
            PaymentFormConfiguration(
                title = "Save a payment method",
                subtitle = "Store a card or bank account and get a reusable token back.",
                submitLabel = "Save payment method",
                allowedMethods = listOf(PaymentMethodType.Card, PaymentMethodType.BankAccount),
                defaultMethod = PaymentMethodType.Card,
                cardSections = listOf(cardDetails(), customerSection),
                bankSections = listOf(bankDetails(), customerSection),
            )

        /** Take a payment now. Same instrument fields, plus what is being charged. */
        fun capture(): PaymentFormConfiguration =
            PaymentFormConfiguration(
                title = "Take a payment",
                subtitle = "Charge a card or bank account.",
                submitLabel = "Submit payment",
                allowedMethods = listOf(PaymentMethodType.Card, PaymentMethodType.BankAccount),
                defaultMethod = PaymentMethodType.Card,
                cardSections = listOf(cardDetails(), customerSection, amountSection()),
                bankSections = listOf(bankDetails(), customerSection, amountSection()),
                summaryValues =
                    mapOf(
                        PaymentField.Amount to "$ 1.00",
                        PaymentField.ServiceFee to "$ 0.10",
                    ),
            )

        private fun cardDetails() =
            PaymentFormSection(
                title = "Card",
                fields =
                    listOf(
                        PaymentField.CardholderName,
                        PaymentField.CardNumber,
                        PaymentField.CardExpiration,
                        PaymentField.CardSecurityCode,
                        PaymentField.CardPostalCode,
                    ),
            )

        private fun bankDetails() =
            PaymentFormSection(
                title = "Bank account",
                fields =
                    listOf(
                        PaymentField.AccountHolder,
                        PaymentField.RoutingNumber,
                        PaymentField.AccountNumber,
                        PaymentField.AccountType,
                    ),
            )

        private fun amountSection() =
            PaymentFormSection(
                title = "Payment",
                fields = listOf(PaymentField.Amount, PaymentField.ServiceFee),
                style = SectionStyle.Summary,
            )
    }
}
