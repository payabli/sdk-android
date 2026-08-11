package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInException
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
 * `null` means the caller did not set one, and is the only way to say that. A value that is blank once trimmed
 * is refused rather than dropped: setting an idempotency key is what makes a caller's retry safe, so silently
 * omitting `" "` would send an unprotected money-moving request to a caller who believes it is protected.
 */
internal fun payInHeaders(build: PayInHeaders.() -> Unit): Map<String, String> = PayInHeaders().apply(build).headers

internal class PayInHeaders {
    val headers: MutableMap<String, String> = LinkedHashMap()

    fun idempotencyKey(value: String?) = put(PayInRoutes.HEADER_IDEMPOTENCY_KEY, value)

    fun validationCode(value: String?) = put(PayInRoutes.HEADER_VALIDATION_CODE, value)

    /**
     * Refuses a value that cannot be a header, before the transport is asked to send it.
     *
     * Both of these come from public request fields, so a caller supplies them. `setRequestProperty` throws
     * `IllegalArgumentException` on an embedded CR or LF, measured on the JVM these tests run on, so
     * `"key\r\nX-Test: v"` would reach a caller as an unchecked exception from inside the transport instead of
     * the typed refusal naming the field that every other bad value produces.
     *
     * The rule is narrower than the platform's: printable ASCII, space and tab. Trimming has already removed
     * the ends, so what is left is interior. Being stricter than the check that provoked this means the answer
     * does not depend on which `HttpURLConnection` is underneath, and a header value outside that range has no
     * use here.
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
