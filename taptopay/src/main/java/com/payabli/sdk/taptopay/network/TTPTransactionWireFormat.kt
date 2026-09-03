// IDE-only workaround, not a compiler requirement: K2's IDE analysis flags the plugin-generated
// $serializer as needing this opt-in, while the compiler exempts it (KTIJ-31549). Remove when fixed.
@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.payabli.sdk.taptopay.network

import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PercentEncoding
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.provider.CardReadResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Amounts carry two decimal places on this wire, and the service reads them as a decimal. */
private const val AMOUNT_SCALE = 2

/**
 * The two MoneyIn routes a card-present charge brackets itself with.
 *
 * [UPDATE] is a template and [update] resolves it. The template is the only form that may be logged: the
 * resolved path carries a transaction identifier.
 */
internal object TTPRoutes {
    const val INITIATE: String = "/api/v2/MoneyIn/initiate"

    const val UPDATE: String = "/api/v2/MoneyIn/update/{transId}"

    /**
     * The resolved path for [UPDATE].
     *
     * The identifier is encoded as one path segment, so a `?`, `#` or `/` in it stays part of the
     * identifier instead of becoming a query, a fragment or another route.
     *
     * **Encoded rather than refused, unlike the entry point on the device routes.** That value comes from
     * the caller, where a shape this SDK did not expect is a caller defect worth naming. This one is read
     * back from a response, so its shape is not this SDK's to assume, and refusing it would fail a charge
     * over a format nobody here controls.
     */
    fun update(paymentTransId: String): String = "/api/v2/MoneyIn/update/" + PercentEncoding.segment(paymentTransId)
}

/**
 * The `paymentMethod.method` a card-present charge sends, and the only one this module can send.
 *
 * It travels beside `paymentMethod.device`, which has to be the identifier registration returned: that
 * pairing is what ties a charge to this reader, and nothing else in the request does.
 */
internal const val PAYMENT_METHOD_DEVICE: String = "device"

/** The wire key the processor's response travels under. See [updateSuccessBody]. */
internal const val FIELD_PROVIDER_RESPONSE: String = "fiservResponse"

/** What the failure report calls itself, and the fixed reason the service files it under. */
internal const val NFC_FAILURE_TITLE: String = "NFC Tap Failed"

internal const val NFC_FAILURE_REASON: String = "nfc_read"

/**
 * The amount as it would reach the wire, or null when it cannot be sent.
 *
 * **Checked at the scale it will be sent at, because that is the value the service acts on.** `0.001` is more
 * than zero and [TTPAmountSerializer] rounds it to `0.00`, so a caller checking the value as supplied opens a
 * payment the service is asked to take as nothing.
 *
 * The two guards exist only so the rounding cannot throw: `setScale` raises `ArithmeticException` at both
 * extremes of the exponent. Both read `precision` and `scale` rather than the expanded value, so an absurd
 * one costs nothing to refuse. Zero is answered first, because it rescales at any scale without expanding
 * and would otherwise be refused for a scale it does not really carry.
 *
 * `:payin` holds an equivalent in `PayInValidation`, with the same bounds and the same measurements behind
 * them. Two copies rather than one because a capability module never depends on a sibling, and neither
 * belongs to `:core` yet. Whichever moves first should take the other with it.
 */
internal fun BigDecimal.sendableAmountOrNull(): BigDecimal? {
    if (signum() == 0) return setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
    if (scale().toLong() > MAX_ROUNDABLE_SCALE) return null
    if (precision().toLong() - scale().toLong() > MAX_INTEGER_DIGITS) return null
    return setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
}

private const val MAX_ROUNDABLE_SCALE = 1_000L

private const val MAX_INTEGER_DIGITS = 1_000L

/**
 * Money on the wire: an unquoted JSON number with exactly two decimal places.
 *
 * `10` is sent as `10.00` and `10.005` as `10.01`. The service's own request payloads write these fields as
 * a decimal literal with two places, so the scale is the contract rather than a formatting preference.
 *
 * **The descriptor names how a value is read, and the write side bypasses it.** Reading accepts a number
 * and a quoted number alike, which is what [PrimitiveKind.STRING] declares; writing goes through
 * [JsonUnquotedLiteral], which does not consult the kind. There is no decimal kind to declare instead, and
 * the one numeric kind that would fit the emitted digits is `DOUBLE`, which asserts the single property a
 * payment amount cannot have.
 *
 * The card-not-present module makes the same two choices for the same reasons. Sharing one serializer would
 * mean a capability module depending on a sibling, which is the one thing the module layout forbids.
 */
internal object TTPAmountSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.payabli.sdk.taptopay.Amount", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        val json =
            encoder as? JsonEncoder
                ?: throw SerializationException("Amounts are only encodable to JSON")
        json.encodeJsonElement(JsonUnquotedLiteral(value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP).toPlainString()))
    }

    /** Tolerant in one direction only: a number and a quoted number both read, anything else is a failure. */
    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: return parse(decoder.decodeString())
        val primitive =
            json.decodeJsonElement() as? JsonPrimitive
                ?: throw SerializationException("An amount is a number, and this was not one")
        return parse(primitive.content)
    }

    /** The text is never in the message: an amount is not a secret, but a decode error is not the place. */
    private fun parse(text: String): BigDecimal =
        text.toBigDecimalOrNull() ?: throw SerializationException("An amount could not be read as a number")
}

/**
 * The request and response shapes of the two MoneyIn routes.
 *
 * **Every key here is lower-camelCase and none of these types needs a `@SerialName`.** These are the same
 * controller and the same envelope the card-not-present module talks to, and the shipping sibling encodes
 * them with default keys.
 *
 * **A request property that must always be present carries no Kotlin default.**
 * [com.payabli.sdk.core.network.PayabliJson] encodes with `encodeDefaults = false`, so a defaulted property
 * is dropped from the body, and `explicitNulls = false` drops a null one. Those two settings are what make
 * "nullable with no default" mean *omitted when unset*, and they are also why the three fields the service
 * wants present-but-empty are non-nullable [String]s that a caller has to supply.
 */
@Serializable
internal class InitiateBody(
    val entryPoint: String,
    val orderDescription: String,
    val paymentDetails: InitiatePaymentDetailsBody,
    val paymentMethod: InitiatePaymentMethodBody,
    val customerData: InitiateCustomerDataBody,
    val invoiceData: InitiateInvoiceDataBody? = null,
) {
    /** Never the contents: `customerData` is the payer's whole record. */
    override fun toString(): String = "InitiateBody"
}

/** [serviceFee] is always written, at zero when nothing was charged for the service. */
@Serializable
internal class InitiatePaymentDetailsBody(
    @Serializable(with = TTPAmountSerializer::class)
    val totalAmount: BigDecimal,
    @Serializable(with = TTPAmountSerializer::class)
    val serviceFee: BigDecimal,
    val currency: String? = null,
    val paymentDescription: String? = null,
) {
    override fun toString(): String = "InitiatePaymentDetailsBody"
}

/** The device-backed flavour of a payment: which registered reader is taking it. */
@Serializable
internal class InitiatePaymentMethodBody(
    val method: String,
    val device: String,
) {
    override fun toString(): String = "InitiatePaymentMethodBody(method=$method)"
}

/**
 * The payer, as the service reads them.
 *
 * **[firstName], [lastName] and [customerNumber] are present even when empty, and the rest are omitted when
 * unset.** That asymmetry is the shipping wire format on a path production uses, and it was measured across
 * two paypoints rather than assumed: changing the three empty strings moved nothing. So tidying them is a
 * wire change with no gain, not a cleanup.
 */
@Serializable
internal class InitiateCustomerDataBody(
    val firstName: String,
    val lastName: String,
    val customerNumber: String,
    val customerId: Long? = null,
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
    /** Every field here is personal data. */
    override fun toString(): String = "InitiateCustomerDataBody"
}

/** Sent only when the caller named an invoice: the whole object is omitted otherwise. */
@Serializable
internal class InitiateInvoiceDataBody(
    val invoiceNumber: String,
) {
    override fun toString(): String = "InitiateInvoiceDataBody"
}

/**
 * The one field a charge reads back.
 *
 * The response carries a whole transaction record and a charge needs one thing from it: the identifier the
 * tap is correlated by, and the path segment the close is sent to. The alternate spellings are the same
 * tolerance the card-not-present module needs on this envelope family.
 */
@Serializable
internal class InitiatePayload(
    @JsonNames("paymenttransid", "PaymentTransId")
    val paymentTransId: String,
) {
    override fun toString(): String = "InitiatePayload"
}

/** A tap that never happened, reported so the opened transaction is not left dangling. */
@Serializable
internal class UpdateFailureBody(
    val error: UpdateErrorDetail,
)

@Serializable
internal class UpdateErrorDetail(
    val title: String,
    val description: String,
    val failureReason: String,
)

/**
 * The success body: the processor's own response, forwarded under [FIELD_PROVIDER_RESPONSE].
 *
 * **The key says one vendor's name and the field is not vendor-specific.** It is the only shape either
 * platform has ever sent, so renaming it is a coordinated change rather than a local one.
 *
 * The response is parsed rather than spliced as text. That is the same output either way, and it means a
 * reader that answered with something other than a JSON object fails here, where the failure names the
 * reader, instead of at the service, where it would arrive as a rejected body.
 */
internal fun updateSuccessBody(result: CardReadResult): JsonObject {
    val response = PayabliJson.format.parseToJsonElement(result.providerResponse)
    if (response !is JsonObject) {
        throw SerializationException("The reader's response is not a JSON object")
    }
    return buildJsonObject { put(FIELD_PROVIDER_RESPONSE, response) }
}

/** The wire shape of [TapToPayPaymentDetails]. */
internal fun TapToPayPaymentDetails.toBody(): InitiatePaymentDetailsBody =
    InitiatePaymentDetailsBody(
        // The wire keeps the service's own key; the surface uses the name both platforms publish.
        totalAmount = amount,
        serviceFee = serviceFee,
        currency = currency.trimOrNull()?.uppercase(),
        paymentDescription = paymentDescription.trimOrNull(),
    )

/**
 * The wire shape of [TapToPayCustomerData].
 *
 * The three always-present fields fall back to empty rather than being omitted; see
 * [InitiateCustomerDataBody].
 */
internal fun TapToPayCustomerData.toBody(): InitiateCustomerDataBody =
    InitiateCustomerDataBody(
        firstName = firstName.trimOrNull().orEmpty(),
        lastName = lastName.trimOrNull().orEmpty(),
        customerNumber = customerNumber.trimOrNull().orEmpty(),
        customerId = customerId,
        company = company.trimOrNull(),
        email = email.trimOrNull(),
        phone = phone.trimOrNull(),
        billingAddress1 = billingAddress1.trimOrNull(),
        billingAddress2 = billingAddress2.trimOrNull(),
        billingCity = billingCity.trimOrNull(),
        billingState = billingState.trimOrNull(),
        billingZip = billingZip.trimOrNull(),
        billingCountry = billingCountry.trimOrNull(),
        billingPhone = billingPhone.trimOrNull(),
        billingEmail = billingEmail.trimOrNull(),
        shippingAddress1 = shippingAddress1.trimOrNull(),
        shippingAddress2 = shippingAddress2.trimOrNull(),
        shippingCity = shippingCity.trimOrNull(),
        shippingState = shippingState.trimOrNull(),
        shippingZip = shippingZip.trimOrNull(),
        shippingCountry = shippingCountry.trimOrNull(),
    )

/** The wire shape of [TapToPayInvoiceData], or nothing at all when no invoice was named. */
internal fun TapToPayInvoiceData.toBody(): InitiateInvoiceDataBody? =
    invoiceNumber.trimOrNull()?.let(::InitiateInvoiceDataBody)

/**
 * The value, or nothing at all where it holds only whitespace.
 *
 * A padded value is not a value, and a field holding one is not a field the service should be asked to
 * store. Blank becomes absent throughout, which is the same rule the card-not-present wire follows.
 */
internal fun String?.trimOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
