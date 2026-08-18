package com.payabli.sdk.taptopay.attestation.device

import java.net.HttpURLConnection.HTTP_FORBIDDEN

/**
 * The one refusal on these routes that means the entry point itself cannot be used.
 *
 * Every route in the family accepts it, because every one of them names an entry point.
 *
 * It classifies that single case and defers everything else, which is what keeps it from re-deciding a refusal
 * something already classifies. Matching text is what [DeviceFailureMapper] exists to contain, and this is the
 * narrowest form of it: one literal, compared whole, at one result code. Exact equality, never a prefix, a
 * case-insensitive compare or a regular expression, for the reason
 * [com.payabli.sdk.taptopay.enrollment.DeviceActivationFailures] gives.
 */
internal object EntryPointFailures : DeviceFailureMapper {
    /**
     * The service's own words, which is both what makes this brittle and what makes it checkable: the live
     * tier calls a route and fails here, rather than against a string a test supplied itself.
     */
    const val ENTRY_POINT_UNUSABLE: String = "Entry point is not available for this request."

    override fun map(
        resultCode: Int?,
        reason: String,
    ): Throwable? =
        if (resultCode == HTTP_FORBIDDEN && reason == ENTRY_POINT_UNUSABLE) {
            DeviceServiceException.EntryPointUnusable(resultCode, reason)
        } else {
            null
        }
}
