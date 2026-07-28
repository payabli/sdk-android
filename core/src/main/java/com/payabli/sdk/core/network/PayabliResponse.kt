package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo

/**
 * The raw result of a transport call. Carries no interpretation: [PayabliHttpErrors] maps a non-2xx
 * status to a typed error, and the caller decides when to apply it.
 *
 * [headers] and [body] are copied at construction, so the producer cannot mutate a response after
 * handing it over. The parameters are deliberately not `val`: exposing them alongside the copies would
 * publish the uncopied originals and defeat the copy. A consumer can still mutate the array it reads
 * from [body], which copying on every access would cost more than it is worth.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliResponse(
    public val statusCode: Int,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0),
) {
    public val headers: Map<String, String> = headers.toMap()
    public val body: ByteArray = body.copyOf()

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
