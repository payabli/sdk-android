package com.payabli.sdk.payin.model

import java.math.BigDecimal

/**
 * What is being charged, and any fee on top of it.
 *
 * [totalAmount] is a [BigDecimal] because a payment amount in binary floating point cannot hold `0.10`. It
 * reaches the wire as an unquoted number with two decimal places.
 *
 * [serviceFee] absent and [serviceFee] zero are different statements, and both are sendable: the service's own
 * payloads send `0.00` explicitly.
 */
public class PayInPaymentDetails(
    public val totalAmount: BigDecimal,
    public val serviceFee: BigDecimal? = null,
    public val currency: String? = null,
    public val checkNumber: String? = null,
    public val checkUniqueId: String? = null,
) {
    /** An amount is not a secret, and it is also not something a log needs from a `toString`. */
    override fun toString(): String = "PayInPaymentDetails(hasServiceFee=${serviceFee != null})"
}

/**
 * Who is paying, as far as the service should record it.
 *
 * Every field is optional because the service decides what it requires per paypoint, and a client refusing a
 * combination the service accepts is a client that has to be updated when a paypoint is reconfigured.
 */
public class PayInCustomerData(
    public val customerId: Long? = null,
    public val customerNumber: String? = null,
    public val firstName: String? = null,
    public val lastName: String? = null,
    public val company: String? = null,
    public val billingEmail: String? = null,
    public val billingPhone: String? = null,
    public val billingAddress1: String? = null,
    public val billingAddress2: String? = null,
    public val billingCity: String? = null,
    public val billingState: String? = null,
    public val billingZip: String? = null,
    public val billingCountry: String? = null,
    public val shippingAddress1: String? = null,
    public val shippingAddress2: String? = null,
    public val shippingCity: String? = null,
    public val shippingState: String? = null,
    public val shippingZip: String? = null,
    public val shippingCountry: String? = null,
    public val additionalData: Map<String, String>? = null,
) {
    /**
     * Every field here is personal data, so none of it is rendered.
     *
     * The one flag says whether the paypoint's own extra fields were supplied, which is a question about
     * configuration rather than about the payer. It reports nothing about whether any customer data was sent.
     */
    override fun toString(): String = "PayInCustomerData(hasAdditionalData=${additionalData != null})"
}

/** The vendor a stored method is being created for, where a paypoint tracks them. */
public class PayInVendorData(
    public val vendorNumber: String? = null,
    public val name: String? = null,
    public val ein: String? = null,
    public val phone: String? = null,
    public val email: String? = null,
)

/**
 * Everything about storing a method other than the instrument itself.
 *
 * No `idempotencyKey`: the service's idempotency middleware covers the MoneyIn paths only, so a key sent to
 * the store endpoint is read by nobody.
 */
public class PayInStoreOptions(
    public val customerData: PayInCustomerData? = null,
    public val vendorData: PayInVendorData? = null,
    public val methodDescription: String? = null,
    public val source: String? = null,
    public val subdomain: String? = null,
    /** Validates a bank account with the service's own check before storing it. */
    public val achValidation: Boolean? = null,
    /** Stores the method against a new anonymous customer when no customer is identified. */
    public val createAnonymous: Boolean? = null,
    public val forceCustomerCreation: Boolean? = null,
    /** Stores it for a single use rather than permanently. */
    public val temporary: Boolean? = null,
    public val fallbackAuth: Boolean? = null,
    public val fallbackAuthAmount: Int? = null,
    /** Skips the card's Luhn check and the bank routing checksum, for a caller that has its own. */
    public val validation: PayInValidationOptions = PayInValidationOptions(),
)

/**
 * Which of the two structural checks run before a request is built.
 *
 * Both default on. They are switchable because each answers a question about a number's shape rather than
 * about an account's existence, and a caller with its own upstream validation should not pay for a second
 * opinion that can only disagree.
 */
public class PayInValidationOptions(
    public val checksCardNumber: Boolean = true,
    public val checksRoutingNumber: Boolean = true,
)

/**
 * Everything a transaction carries except the instrument being charged.
 *
 * The parallel of [PayInStoreOptions]. A payment form supplies the instrument and this is the rest, so the
 * two halves meet in one place and a caller configuring a screen never holds a card number.
 *
 * The flags and headers are the service's own parameters.
 */
public class PayInTransactionOptions(
    public val paymentDetails: PayInPaymentDetails,
    public val customerData: PayInCustomerData? = null,
    public val accountId: String? = null,
    public val ipAddress: String? = null,
    public val orderId: String? = null,
    public val orderDescription: String? = null,
    public val source: String? = null,
    public val subdomain: String? = null,
    public val subscriptionId: Long? = null,
    /**
     * Makes a repeated request return the first one's result instead of acting again.
     *
     * One key covers both operations it serves: a capture repeated without one charges twice, and an
     * authorization repeated without one places a second hold on the payer's funds.
     *
     * Sent as the `idempotencyKey` header, which is the spelling the service reads. **Not** a client-side
     * retry: nothing in this module retries either call, and this is what makes a caller's own retry safe.
     */
    public val idempotencyKey: String? = null,
    public val achValidation: Boolean? = null,
    public val forceCustomerCreation: Boolean? = null,
    /** Settles a bank debit the same day where the paypoint is configured for it. */
    public val sameDayAch: Boolean? = null,
    /** Returns before the processor answers, leaving the result to be read later. */
    public val isAsync: Boolean? = null,
    public val useCaching: Boolean? = null,
    /** Sent as the `validationCode` header, for a paypoint that requires one. */
    public val validationCode: String? = null,
    public val validation: PayInValidationOptions = PayInValidationOptions(),
)

/**
 * A transaction to capture or authorize: the instrument, and [PayInTransactionOptions] for the rest.
 *
 * Two parts rather than one flat list, because the two halves come from different places and at different
 * times. A knob added to a transaction is added to [options] alone, so the two cannot drift.
 */
public class PayInRequest(
    public val paymentMethod: PayInPaymentMethod,
    public val options: PayInTransactionOptions,
) {
    public val paymentDetails: PayInPaymentDetails get() = options.paymentDetails
}

/**
 * An authorization to capture, in full or in part.
 *
 * [transId] is the service's identifier for the authorization and goes in the path, which is what makes this
 * the one call in the module whose resolved path differs from its route template.
 */
public class PayInAuthorizedRequest(
    public val transId: String,
    public val paymentDetails: PayInPaymentDetails,
    /**
     * Makes a repeated capture return the first one's result instead of capturing again.
     *
     * This route is under the same idempotency middleware as a capture, and it moves money: a response lost
     * on the way back leaves a caller unable to retry without risking a second partial capture.
     */
    public val idempotencyKey: String? = null,
)
