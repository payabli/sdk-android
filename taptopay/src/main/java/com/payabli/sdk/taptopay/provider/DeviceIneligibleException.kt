package com.payabli.sdk.taptopay.provider

import com.payabli.sdk.core.model.PayabliErrorCode

/**
 * This handset cannot take contactless payments.
 *
 * Distinct from every other session failure because it is the only one where nothing a host does will help:
 * the account is fine, the service is fine, and the SDK is fine. It is the wrong hardware, or a platform
 * that is missing something the reader needs.
 *
 * [message] says which check failed. It never names the device, so it is safe in a message a host displays.
 * [code] is the cause in the catalog, which the check that failed is the one place to know.
 */
internal class DeviceIneligibleException(
    val code: PayabliErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
