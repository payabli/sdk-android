package com.payabli.sdk.taptopay.model

import java.math.BigDecimal

/**
 * What is being charged.
 *
 * [amount] is a [BigDecimal] and never a `Double`: binary floating point cannot hold `0.10`, which is not
 * a property a payment amount can afford.
 *
 * Leaving [currency] unset lets the service authorize in the paypoint's own currency, which is the one the
 * reader was configured with.
 */
public class TapToPayPaymentDetails(
    public val amount: BigDecimal,
    public val serviceFee: BigDecimal = BigDecimal.ZERO,
    public val currency: String? = null,
    public val paymentDescription: String? = null,
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
public class TapToPayCustomerData(
    public val customerId: Long? = null,
    public val customerNumber: String? = null,
    public val firstName: String? = null,
    public val lastName: String? = null,
    public val company: String? = null,
    public val email: String? = null,
    public val phone: String? = null,
    public val billingAddress1: String? = null,
    public val billingAddress2: String? = null,
    public val billingCity: String? = null,
    public val billingState: String? = null,
    public val billingZip: String? = null,
    public val billingCountry: String? = null,
    public val billingPhone: String? = null,
    public val billingEmail: String? = null,
    public val shippingAddress1: String? = null,
    public val shippingAddress2: String? = null,
    public val shippingCity: String? = null,
    public val shippingState: String? = null,
    public val shippingZip: String? = null,
    public val shippingCountry: String? = null,
) {
    /** Every field here is personal data, so none of them is printed. */
    override fun toString(): String = "TapToPayCustomerData"
}

/** What the payment settles, where the paypoint tracks invoices. */
public class TapToPayInvoiceData(
    public val invoiceNumber: String? = null,
) {
    override fun toString(): String = "TapToPayInvoiceData"
}
