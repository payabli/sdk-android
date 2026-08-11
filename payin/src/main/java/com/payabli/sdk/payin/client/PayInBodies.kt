package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInVendorData

/**
 * Model to wire, for the parts both clients send.
 *
 * Separate from either client because storing a method and taking a payment send the same customer and the
 * same amounts, and a second copy of either mapping is a second place for a field to be forgotten.
 *
 * Blank strings become absent throughout. A field the payer left empty and a field the caller never set are
 * the same statement to the service, and sending `""` makes some of its validators refuse a value it would
 * otherwise have defaulted.
 */
internal fun PayInPaymentDetails.toBody(): PaymentDetailsBody =
    PaymentDetailsBody(
        totalAmount = totalAmount,
        serviceFee = serviceFee,
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

/** The service reads these as text, so a flag is `true` or `false` rather than `1` or `0`. */
internal fun Boolean.wire(): String = if (this) "true" else "false"

/** Trimmed, or absent when nothing is left. */
internal fun String.trimOrNull(): String? = trim().takeIf { it.isNotEmpty() }

/**
 * The headers a caller contributes, which are the only ones a client here sets.
 *
 * `Authorization` and the JSON content type are chain steps in `:core`, applied to every request, so a
 * client that added either would be duplicating a decision made once for the whole SDK.
 *
 * A blank value is left out rather than sent empty: an empty idempotency key would key the service's
 * middleware on nothing.
 */
internal fun payInHeaders(build: PayInHeaders.() -> Unit): Map<String, String> = PayInHeaders().apply(build).headers

internal class PayInHeaders {
    val headers: MutableMap<String, String> = LinkedHashMap()

    fun idempotencyKey(value: String?) {
        value?.trimOrNull()?.let { headers[PayInRoutes.HEADER_IDEMPOTENCY_KEY] = it }
    }

    fun validationCode(value: String?) {
        value?.trimOrNull()?.let { headers[PayInRoutes.HEADER_VALIDATION_CODE] = it }
    }
}
