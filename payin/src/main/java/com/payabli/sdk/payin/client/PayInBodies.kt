package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInVendorData

/**
 * Model to wire, for the parts both clients send, blank strings absent throughout: a field the payer
 * left empty and one the caller never set are the same statement, and sending `""` states something
 * else. A second copy of either mapping is a second place for a field to be forgotten.
 *
 * @return the wire body for the payment details.
 */
internal fun PayInPaymentDetails.toBody(): PaymentDetailsBody =
    PaymentDetailsBody(
        totalAmount = totalAmount,
        serviceFee = serviceFee,
        surchargeFee = surchargeFee,
        currency = currency?.trimOrNull(),
        checkNumber = checkNumber?.trimOrNull(),
        checkUniqueId = checkUniqueId?.trimOrNull(),
    )

internal fun PayInCustomerData.toBody(): CustomerDataBody =
    CustomerDataBody(
        customerId = customerId,
        customerNumber = customerNumber?.trimOrNull(),
        firstName = firstName?.trimOrNull(),
        lastName = lastName?.trimOrNull(),
        company = company?.trimOrNull(),
        billingEmail = billingEmail?.trimOrNull(),
        billingPhone = billingPhone?.trimOrNull(),
        billingAddress1 = billingAddress1?.trimOrNull(),
        billingAddress2 = billingAddress2?.trimOrNull(),
        billingCity = billingCity?.trimOrNull(),
        billingState = billingState?.trimOrNull(),
        billingZip = billingZip?.trimOrNull(),
        billingCountry = billingCountry?.trimOrNull(),
        shippingAddress1 = shippingAddress1?.trimOrNull(),
        shippingAddress2 = shippingAddress2?.trimOrNull(),
        shippingCity = shippingCity?.trimOrNull(),
        shippingState = shippingState?.trimOrNull(),
        shippingZip = shippingZip?.trimOrNull(),
        shippingCountry = shippingCountry?.trimOrNull(),
        additionalData = additionalData?.takeIf { it.isNotEmpty() },
    )

internal fun PayInVendorData.toBody(): VendorDataBody =
    VendorDataBody(
        vendorNumber = vendorNumber?.trimOrNull(),
        name = name?.trimOrNull(),
        ein = ein?.trimOrNull(),
        phone = phone?.trimOrNull(),
        email = email?.trimOrNull(),
    )

/**
 * The service reads these as text, so a flag is `true` or `false` rather than `1` or `0`.
 *
 * @return `true` or `false`, as the service reads a flag.
 */
internal fun Boolean.wire(): String = if (this) "true" else "false"

/**
 * Trimmed, or absent when nothing is left.
 *
 * @return the trimmed value, or null when nothing is left.
 */
internal fun String.trimOrNull(): String? = trim().takeIf { it.isNotEmpty() }

/**
 * The headers a caller contributes, which are the only ones a client here sets: `Authorization` and
 * the JSON content type are chain steps in `:core`. A value blank once trimmed is refused rather than
 * dropped, because setting an idempotency key is what makes a caller's retry safe.
 *
 * @param build the headers to set.
 * @return the headers the caller contributed.
 */
internal fun payInHeaders(build: PayInHeaders.() -> Unit): Map<String, String> = PayInHeaders().apply(build).headers

internal class PayInHeaders {
    val headers: MutableMap<String, String> = LinkedHashMap()

    fun idempotencyKey(value: String?) = put(PayInRoutes.HEADER_IDEMPOTENCY_KEY, value)

    fun validationCode(value: String?) = put(PayInRoutes.HEADER_VALIDATION_CODE, value)

    /**
     * Refuses a value that cannot be a header, before the transport is asked to send it: printable
     * ASCII, space and tab, stricter than the platform's check, so the answer does not depend on
     * which `HttpURLConnection` is underneath.
     *
     * @param name the header's name, for the typed refusal.
     * @param value the header's value, or null to set none.
     */
    private fun put(
        name: String,
        value: String?,
    ) {
        if (value == null) return
        val trimmed =
            value.trimOrNull()
                ?: throw PayInException.InvalidInput(name, "The $name cannot be blank")
        if (trimmed.any { it != '\t' && (it < ' ' || it > '~') }) {
            throw PayInException.InvalidInput(name, "The $name may contain printable ASCII only")
        }
        headers[name] = trimmed
    }
}
