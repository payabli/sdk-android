package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo

/**
 * The raw result of a transport call. Carries no interpretation: [PayabliHttpErrors] maps a non-2xx
 * status to a typed error, and the caller decides when to apply it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliResponse(
    public val statusCode: Int,
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray = ByteArray(0),
) {
    /** True for 2xx. */
    public val isSuccessful: Boolean get() = statusCode in SUCCESS_RANGE

    /**
     * Case-insensitive header lookup. RFC 9110 defines header names as case-insensitive, and
     * `HttpURLConnection` does not normalize the case it reports.
     */
    public fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The body decoded as UTF-8. */
    public fun bodyAsText(): String = body.toString(Charsets.UTF_8)

    /** Never includes headers or body: a response body may carry cardholder data. */
    override fun toString(): String = "PayabliResponse(status=$statusCode, bodyBytes=${body.size})"

    private companion object {
        private val SUCCESS_RANGE = 200..299
    }
}
