// @JsonNames is experimental, and it is what lets a response field arrive in a casing the service changes
// its mind about. `useAlternativeNames` is on by default, so nothing else has to be configured.
@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package com.payabli.sdk.payin.client

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.math.BigDecimal

/**
 * Every field name on the wire, in one file.
 *
 * A path and a field name are the two things a reviewer cannot check by reading the client, so they are not
 * spread across the code that uses them. `:taptopay` keeps its device wire format the same way.
 *
 * **The casing is the service's, not a transcription slip.** `cardnumber`, `cardexp`, `cardcvv` and `cardzip`
 * are lower case while `cardHolder` and `achHolder` are camel; `achAccountType` values are capitalised while
 * `achHolderType` values are not. Verified in the service's own source and its own test payloads rather than
 * copied from the sibling platform. The Kotlin properties stay consistent, so none of that reaches a caller.
 *
 * Requests send exactly what the service reads. Responses additionally accept the other casings through
 * [JsonNames], so a field the service re-spells costs nothing instead of silently reading null.
 */
internal object PayInRoutes {
    /** Templates, and for three of the four also the paths. Only [CAPTURE_AUTHORIZED] embeds an identifier. */
    const val STORE_METHOD: String = "/api/TokenStorage/add"
    const val CAPTURE: String = "/api/v2/MoneyIn/getpaid"
    const val AUTHORIZE: String = "/api/v2/MoneyIn/authorize"
    const val CAPTURE_AUTHORIZED: String = "/api/v2/MoneyIn/capture/{transId}"

    /** The resolved path for [CAPTURE_AUTHORIZED]. The template is what a log may carry. */
    fun captureAuthorized(transId: String): String = "/api/v2/MoneyIn/capture/$transId"

    /** The service reads this spelling, not `Idempotency-Key`. Its middleware covers the MoneyIn paths only. */
    const val HEADER_IDEMPOTENCY_KEY: String = "idempotencyKey"

    /** A header rather than a query flag, for a paypoint that requires one. */
    const val HEADER_VALIDATION_CODE: String = "validationCode"

    const val QUERY_ACH_VALIDATION: String = "achValidation"
    const val QUERY_FORCE_CUSTOMER_CREATION: String = "forceCustomerCreation"
    const val QUERY_CREATE_ANONYMOUS: String = "createAnonymous"
    const val QUERY_TEMPORARY: String = "temporary"
    const val QUERY_SAME_DAY_ACH: String = "sameDayACH"
    const val QUERY_IS_ASYNC: String = "isAsync"
    const val QUERY_USE_CACHING: String = "useCaching"

    /** `paymentMethod`, the one member the body writer adds itself, because two of its fields are buffers. */
    const val FIELD_PAYMENT_METHOD: String = "paymentMethod"

    const val FIELD_METHOD: String = "method"
    const val FIELD_CARD_NUMBER: String = "cardnumber"
    const val FIELD_CARD_EXPIRY: String = "cardexp"
    const val FIELD_CARD_SECURITY_CODE: String = "cardcvv"
    const val FIELD_CARD_HOLDER: String = "cardHolder"
    const val FIELD_CARD_POSTAL_CODE: String = "cardzip"
    const val FIELD_ACH_ACCOUNT: String = "achAccount"
    const val FIELD_ACH_ACCOUNT_TYPE: String = "achAccountType"
    const val FIELD_ACH_ROUTING: String = "achRouting"
    const val FIELD_ACH_HOLDER: String = "achHolder"
    const val FIELD_ACH_HOLDER_TYPE: String = "achHolderType"
    const val FIELD_ACH_SEC_CODE: String = "achCode"
    const val FIELD_DEVICE: String = "device"
    const val FIELD_STORED_METHOD_ID: String = "storedMethodId"
    const val FIELD_CHECK_HOLDER: String = "checkHolder"

    const val METHOD_CARD: String = "card"
    const val METHOD_ACH: String = "ach"
    const val METHOD_STORED: String = "stored"
    const val METHOD_CLOUD: String = "cloud"
    const val METHOD_CHECK: String = "check"
    const val METHOD_CASH: String = "cash"
}

/**
 * The media type every request here carries.
 *
 * `PayabliRequest.json` adds it, and these bodies are assembled as bytes rather than through that helper, so
 * they have to add it themselves. The transport forwards the headers a request carries and sets none of its
 * own, and `HttpURLConnection` defaults an unset content type to form encoding, which the service reads as
 * an unsupported media type rather than as JSON.
 */
internal val JSON_CONTENT_TYPE: Map<String, String> =
    mapOf(PayabliRequest.CONTENT_TYPE_HEADER to PayabliRequest.APPLICATION_JSON)

/** What is being charged. The two amounts are unquoted numbers with two decimal places. */
@Serializable
internal class PaymentDetailsBody(
    @Serializable(with = PayInAmountSerializer::class)
    val totalAmount: BigDecimal,
    @Serializable(with = PayInAmountSerializer::class)
    val serviceFee: BigDecimal? = null,
    val currency: String? = null,
    val checkNumber: String? = null,
    val checkUniqueId: String? = null,
)

/** Who is paying. Every field optional, because which ones a paypoint requires is the service's business. */
@Serializable
internal class CustomerDataBody(
    val customerId: Long? = null,
    val customerNumber: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val company: String? = null,
    val billingEmail: String? = null,
    val billingPhone: String? = null,
    val billingAddress1: String? = null,
    val billingAddress2: String? = null,
    val billingCity: String? = null,
    val billingState: String? = null,
    val billingZip: String? = null,
    val billingCountry: String? = null,
    val shippingAddress1: String? = null,
    val shippingAddress2: String? = null,
    val shippingCity: String? = null,
    val shippingState: String? = null,
    val shippingZip: String? = null,
    val shippingCountry: String? = null,
    val additionalData: Map<String, String>? = null,
)

/** The vendor a stored method belongs to, where a paypoint tracks them. */
@Serializable
internal class VendorDataBody(
    val vendorNumber: String? = null,
    val name: String? = null,
    val ein: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

/**
 * A capture or an authorisation, **without** `paymentMethod`.
 *
 * Two of `paymentMethod`'s fields are buffers, so [PayInBodyWriter] appends that member as bytes.
 */
@Serializable
internal class MoneyInBody(
    val entryPoint: String,
    val paymentDetails: PaymentDetailsBody,
    val customerData: CustomerDataBody? = null,
    val accountId: String? = null,
    val ipaddress: String? = null,
    val orderId: String? = null,
    val orderDescription: String? = null,
    val source: String? = null,
    val subdomain: String? = null,
    val subscriptionId: Long? = null,
)

/** Storing a method, also without `paymentMethod`, for the same reason. */
@Serializable
internal class StoreMethodBody(
    val entryPoint: String,
    val customerData: CustomerDataBody? = null,
    val vendorData: VendorDataBody? = null,
    val methodDescription: String? = null,
    val fallbackAuth: Boolean? = null,
    val fallbackAuthAmount: Int? = null,
    val source: String? = null,
    val subdomain: String? = null,
)

/** Capturing an authorisation carries only the amount: the method was settled when it was authorised. */
@Serializable
internal class AuthorizedCaptureBody(
    val paymentDetails: PaymentDetailsBody,
)

/**
 * The transaction record a v2 approval carries in `data`.
 *
 * The v2 payload is the service's detailed transaction record, which carries no authorisation code, AVS result
 * or security-code result. Those three are in the older response shape only.
 */
@Serializable
internal class TransactionPayload(
    @JsonNames("paymenttransid", "PaymentTransId")
    val paymentTransId: String? = null,
    @JsonNames("gatewaytransid", "GatewayTransId")
    val gatewayTransId: String? = null,
    @JsonNames("orderid", "OrderId")
    val orderId: String? = null,
    @JsonNames("Method")
    val method: String? = null,
    @JsonNames("transstatus", "TransStatus")
    val transStatus: Int? = null,
    @JsonNames("paypointid", "PaypointId")
    val paypointId: Long? = null,
    @JsonNames("totalamount", "TotalAmount")
    @Serializable(with = PayInAmountSerializer::class)
    val totalAmount: BigDecimal? = null,
    @JsonNames("netamount", "NetAmount")
    @Serializable(with = PayInAmountSerializer::class)
    val netAmount: BigDecimal? = null,
    @JsonNames("connectorname", "ConnectorName")
    val connectorName: String? = null,
    @JsonNames("payorid", "PayorId")
    val payorId: Long? = null,
)

/** The `responseData` of a stored-method reply. */
@Serializable
internal class StoredMethodPayload(
    @JsonNames("referenceid", "ReferenceId")
    val referenceId: String? = null,
    @JsonNames("methodreferenceid", "MethodReferenceId")
    val methodReferenceId: Long? = null,
    @JsonNames("customerid", "CustomerId")
    val customerId: Long? = null,
    @JsonNames("resultcode", "ResultCode")
    val resultCode: Int? = null,
    @JsonNames("resulttext", "ResultText")
    val resultText: String? = null,
)

/**
 * The stored-method reply, which is the older envelope rather than the v2 one.
 *
 * The two endpoints this module calls answer in different envelopes, and `:core` models both separately:
 * storing a method reports a refusal as `isSuccess: false` behind a 200, while a transaction reports it as a
 * `D`-prefixed code. Do not conflate them.
 */
@Serializable
internal class StoredMethodEnvelope(
    @JsonNames("issuccess", "IsSuccess")
    val isSuccess: Boolean? = null,
    @JsonNames("responsetext", "ResponseText")
    val responseText: String? = null,
    @JsonNames("responsedata", "ResponseData")
    val responseData: StoredMethodPayload? = null,
)
