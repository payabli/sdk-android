package com.payabli.sdk.taptopay.model

import java.math.BigDecimal

/**
 * What is being charged.
 *
 * [totalAmount] is a [BigDecimal] and never a `Double`, for the reason the card-not-present module already
 * states: binary floating point cannot hold `0.10`, which is not a property a payment amount can afford.
 *
 * Leaving [currency] unset lets the service authorize in the paypoint's own currency, which is the one the
 * reader was configured with.
 */
internal class TapToPayPaymentDetails(
    val totalAmount: BigDecimal,
    val serviceFee: BigDecimal = BigDecimal.ZERO,
    val currency: String? = null,
    val paymentDescription: String? = null,
) {
    /** Never the amounts: what is being charged is transaction data, and this reaches diagnostics. */
    override fun toString(): String = "TapToPayPaymentDetails"
}

/**
 * Who is paying.
 *
 * Every field is optional, because which of them a paypoint requires is the service's business and it
 * differs between them. Nothing here is validated locally for that reason.
 */
internal class TapToPayCustomerData(
    val customerId: Long? = null,
    val customerNumber: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val billingAddress1: String? = null,
    val billingAddress2: String? = null,
    val billingCity: String? = null,
    val billingState: String? = null,
    val billingZip: String? = null,
    val billingCountry: String? = null,
    val billingPhone: String? = null,
    val billingEmail: String? = null,
    val shippingAddress1: String? = null,
    val shippingAddress2: String? = null,
    val shippingCity: String? = null,
    val shippingState: String? = null,
    val shippingZip: String? = null,
    val shippingCountry: String? = null,
) {
    /** Every field here is personal data, so none of them is printed. */
    override fun toString(): String = "TapToPayCustomerData"
}

/** What the payment settles, where the paypoint tracks invoices. */
internal class TapToPayInvoiceData(
    val invoiceNumber: String? = null,
) {
    override fun toString(): String = "TapToPayInvoiceData"
}
