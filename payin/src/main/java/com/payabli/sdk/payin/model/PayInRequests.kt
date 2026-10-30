package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliAttempt
import java.math.BigDecimal

/**
 * What is being charged: [totalAmount], with [serviceFee] and any [surchargeFee] counted inside it.
 *
 * [totalAmount] is a [BigDecimal] because a payment amount in binary floating point cannot hold `0.10`. It
 * reaches the wire as an unquoted number with two decimal places.
 *
 * [serviceFee] absent and [serviceFee] zero are different statements, and both are sendable.
 */
public class PayInPaymentDetails(
    public val totalAmount: BigDecimal,
    public val serviceFee: BigDecimal? = null,
    public val surchargeFee: BigDecimal? = null,
    public val currency: String? = null,
    public val checkNumber: String? = null,
    public val checkUniqueId: String? = null,
) {
    /**
     * An amount is not a secret, and it is also not something a log needs from a `toString`.
     *
     * @return whether a service fee was supplied, never the amount.
     */
    override fun toString(): String = "PayInPaymentDetails(hasServiceFee=${serviceFee != null})"
}

/**
 * Who is paying, as far as the transaction should record it.
 *
 * Every field is optional because what is required varies by paypoint, and a client refusing a combination
 * that would have been accepted is a client that has to be updated when a paypoint is reconfigured.
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
     *
     * @return whether the paypoint's own extra fields were supplied, never a field of the payer.
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
 * No `idempotencyKey`: a repeat is not recognizable on the store route, so a key sent there is read by
 * nobody.
 *
 * @property achValidation checks a bank account before it is stored. On this call nothing else asks for the
 *   check, so a method stored without it is not checked.
 * @property createAnonymous stores the method against a new anonymous customer when no customer is identified.
 * @property temporary stores it for a single use rather than permanently.
 * @property validation which structural checks run; both run by default, and a flag set false skips its own.
 */
public class PayInStoreOptions(
    public val customerData: PayInCustomerData? = null,
    public val vendorData: PayInVendorData? = null,
    public val methodDescription: String? = null,
    public val source: String? = null,
    public val subdomain: String? = null,
    public val achValidation: Boolean? = null,
    public val createAnonymous: Boolean? = null,
    public val forceCustomerCreation: Boolean? = null,
    public val temporary: Boolean? = null,
    public val fallbackAuth: Boolean? = null,
    public val fallbackAuthAmount: Int? = null,
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
 * What to charge, who to charge it for, and how the request should be treated.
 *
 * The instrument is not here: a payment form collects that and hands it over at submission, so a caller
 * configuring a screen ahead of time holds no card number.
 *
 * The flags and headers below are wire parameters, and this type only carries them.
 *
 * @property achValidation checks a bank account before it is debited, on top of any check the merchant's own
 *   settings already run. `false` does not turn that check off.
 * @property forceCustomerCreation applies only when the payer cannot be matched to an existing customer: `true`
 *   creates a new customer record, a new one on each such payment.
 * @property attempt whether this is a new payment or a retry of the payment [paymentReference] names. A retry
 *   must set [paymentReference].
 * @property paymentReference the caller's own name for this payment, never sent. A retry naming it resends
 *   the key held for it. When unset on a new payment, the SDK mints one and returns it with an unknown outcome;
 *   unset on a retry, or blank, is refused before sending.
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
    public val achValidation: Boolean? = null,
    public val forceCustomerCreation: Boolean? = null,
    public val validation: PayInValidationOptions = PayInValidationOptions(),
    public val attempt: PayabliAttempt = PayabliAttempt.NewPayment,
    public val paymentReference: String? = null,
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

/** A method to store: the instrument, and [PayInStoreOptions] for the rest. */
public class PayInStoreRequest(
    public val instrument: PayInInstrument,
    public val options: PayInStoreOptions = PayInStoreOptions(),
)

/**
 * An authorization to capture, in full or in part.
 *
 * [transId] identifies the authorization and goes in the path, so the resolved path differs from the route
 * template this call is recorded under. Reversing a transaction is the other one shaped that way.
 *
 * The SDK mints the capture's idempotency key and holds it for [transId] while the outcome is unknown, so
 * calling again with the same [transId] is the same capture rather than a second one. It is held in memory
 * and does not outlive the process.
 */
public class PayInAuthorizedRequest(
    public val transId: String,
    public val paymentDetails: PayInPaymentDetails,
)
