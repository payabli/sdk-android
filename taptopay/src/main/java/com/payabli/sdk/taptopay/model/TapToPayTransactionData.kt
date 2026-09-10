package com.payabli.sdk.taptopay.model

import java.math.BigDecimal

/**
 * What is being charged.
 *
 * **[amount] is the whole of what the payer is charged, and [serviceFee] is the part of it charged for the
 * service.** A fee is not added to the amount by this SDK or by the service: the card is asked for [amount],
 * and [amount] is what the payment is opened for, with [serviceFee] travelling beside it so the paypoint
 * records how much of that total was the fee. A caller passing a base amount and a fee to be added on charges
 * the base and records a fee it never took. The card-not-present side names the same field `totalAmount`,
 * which says this in the name; here it is said once, in words.
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
 * Which fields a paypoint requires is the service's business and differs between them, so every field is
 * optional and none is checked against a paypoint's rules here.
 *
 * **One thing is checked: that the payer is identified at all.** A charge naming nobody is refused by every
 * paypoint this has been sent to, and refused on the call that opens the payment — after the terminal is set
 * up and before a card is asked for. Nothing has been taken from anyone at that point, so what the local
 * check saves is a round trip and a reader session rather than a payment.
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

/**
 * Whether this names a payer at all.
 *
 * [customerId] counts, even though only the other three are measured. What is known is that a body whose
 * `firstName`, `lastName` and `customerNumber` are all empty is refused; whether an id alone is enough is
 * the service's answer to give. Refusing it here would break a caller the service would have accepted,
 * which is worse than the late refusal this check exists to avoid.
 */
internal val TapToPayCustomerData.identifiesSomeone: Boolean
    get() =
        customerId != null ||
            !customerNumber.isNullOrBlank() ||
            !firstName.isNullOrBlank() ||
            !lastName.isNullOrBlank()

/** What the payment settles, where the paypoint tracks invoices. */
public class TapToPayInvoiceData(
    public val invoiceNumber: String? = null,
) {
    override fun toString(): String = "TapToPayInvoiceData"
}
