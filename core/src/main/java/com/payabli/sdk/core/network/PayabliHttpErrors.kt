// IDE-only workaround, not a compiler requirement: K2's IDE analysis flags the plugin-generated
// $serializer as needing this opt-in, while the compiler exempts it (KTIJ-31549). Remove when fixed.
@file:OptIn(InternalSerializationApi::class)

package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliFieldError
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliRateLimitException
import com.payabli.sdk.core.model.PayabliServerException
import com.payabli.sdk.core.model.PayabliValidationException
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_FORBIDDEN = 403
private const val HTTP_GONE = 410
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_INTERNAL_ERROR = 500

private const val RETRY_AFTER_HEADER = "Retry-After"
private const val MILLIS_PER_SECOND = 1_000L

/** Guards the seconds-to-millis multiply against overflow on an absurd value. */
private const val MAX_RETRY_AFTER_SECONDS = Long.MAX_VALUE / MILLIS_PER_SECOND

/** IMF-fixdate first, then the two obsolete forms RFC 9110 still requires recipients to accept. */
private val HTTP_DATE_FORMATS =
    listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )

/**
 * Maps an HTTP status to a typed [PayabliException].
 *
 * Contract-zone rather than `internal` because endpoint clients in `:payin` and `:taptopay` call it, and
 * `internal` is module-scoped.
 *
 * ### This mapper reads the status line only
 *
 * A 2xx returns null unconditionally, which is **not** the same as success:
 *
 * - the legacy device routes report business failures as HTTP 200 with `isSuccess: false`, which
 *   [PayabliEnvelope.declineOutcome] surfaces and this mapper cannot see;
 * - a v2 envelope can arrive on a 2xx with a `D`-prefixed code, which [PayabliV2Envelope.isDeclined]
 *   surfaces.
 *
 * Both are the endpoint client's job. The usual sequence is:
 *
 * ```kotlin
 * val response = transport.execute(request)
 * PayabliHttpErrors.from(response)?.let { throw it }
 * PayabliEnvelope.declineOutcome(response.body)?.let { /* legacy in-body failure */ }
 * ```
 *
 * ### Mapping
 *
 * | Status | Result |
 * |---|---|
 * | 2xx | `null` |
 * | 400 | [PayabliValidationException] |
 * | 401 | [PayabliGenericException], [PayabliErrorCode.TOKEN_EXPIRED] |
 * | 402 | [PayabliDeclineException] |
 * | 403 | [PayabliGenericException], [PayabliErrorCode.PERMISSION_DENIED] |
 * | 410 | [PayabliGenericException], [PayabliErrorCode.SESSION_BURNED] |
 * | >= 500 | [PayabliServerException] |
 * | any other non-2xx | [PayabliGenericException], [PayabliErrorCode.UNKNOWN] |
 *
 * **The status alone fixes the classification; the body only decides how many fields get filled.** A
 * malformed body costs fields, never the code, so a caller's `when (code)` branch cannot flip because a
 * proxy returned HTML.
 *
 * 410 is mapped to the specification. No endpoint has been observed producing one, so treat that row as
 * specified rather than confirmed.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliHttpErrors {
    /**
     * Returns the typed error for [response], or null when [response] is 2xx.
     *
     * Returns rather than throws: a returning mapper is directly assertable in a test and composes with
     * `?.let`. Throwing is one token at the call site, `from(response)?.let { throw it }`.
     *
     * [statusOverride] is consulted first for any non-2xx and wins when it returns non-null, which is
     * how a capability module attaches component meaning to a shared status. It is not consulted for a
     * 2xx, so it cannot manufacture an error out of a success.
     *
     * Never throws: a body that will not decode degrades to fewer fields.
     */
    public fun from(
        response: PayabliResponse,
        statusOverride: (statusCode: Int) -> PayabliException? = NO_OVERRIDE,
    ): PayabliException? {
        if (response.isSuccessful) return null
        val status = response.statusCode
        statusOverride(status)?.let { return it }
        val body = response.bodyAsText()
        return when {
            status == HTTP_BAD_REQUEST -> validation(status, body)
            status == HTTP_UNAUTHORIZED -> PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "Unauthorized (401)")
            status == HTTP_PAYMENT_REQUIRED -> decline(body)
            status == HTTP_FORBIDDEN -> PayabliGenericException(PayabliErrorCode.PERMISSION_DENIED, "Forbidden (403)")
            status == HTTP_GONE -> PayabliGenericException(PayabliErrorCode.SESSION_BURNED, "Session burned (410)")
            status == HTTP_TOO_MANY_REQUESTS -> PayabliRateLimitException(retryAfterMillis(response))
            status >= HTTP_INTERNAL_ERROR -> server(status, body, retryAfterMillis(response))
            else -> PayabliGenericException(PayabliErrorCode.UNKNOWN, "HTTP $status")
        }
    }

    /**
     * The `application/problem+json` body (RFC 9457) plus Payabli's `code`, deliberately without `errors`.
     *
     * Split from [ErrorsMap] because `errors` is the field most likely to arrive in an
     * unexpected shape, and a single DTO would lose `title` and `detail` along with it. The body's own
     * `status` is dropped because the response carries it, and `token` is dropped because it is a
     * temporary page identifier that must not land on a `Throwable`.
     */
    @Serializable
    internal class ErrorDetails(
        val title: String? = null,
        val detail: String? = null,
        val type: String? = null,
        val instance: String? = null,
        @SerialName("code")
        val rawCode: String? = null,
    )

    /** The `errors` map alone, so an unexpected shape there costs only the field list. */
    @Serializable
    internal class ErrorsMap(
        val errors: Map<String, List<PayabliFieldError>>? = null,
    )

    /**
     * The 402 body. A dedicated shape rather than reusing [PayabliV2Envelope], so a decline can never
     * retain the `data` blob and an absent `code` degrades a field instead of failing the decode.
     */
    @Serializable
    internal class DeclineBody(
        @SerialName("code")
        val rawCode: String? = null,
        val reason: String? = null,
        val explanation: String? = null,
        val action: String? = null,
    )

    private fun validation(
        status: Int,
        body: String,
    ): PayabliValidationException {
        val errorDetails = decodeOrNull(ErrorDetails.serializer(), body)
        return PayabliValidationException(
            httpStatus = status,
            reason = errorDetails?.title ?: PayabliValidationException.DEFAULT_REASON,
            detail = errorDetails?.detail,
            type = errorDetails?.type,
            instance = errorDetails?.instance,
            rawCode = errorDetails?.rawCode,
            fieldErrors = decodeOrNull(ErrorsMap.serializer(), body)?.errors ?: emptyMap(),
        )
    }

    private fun server(
        status: Int,
        body: String,
        retryAfterMillis: Long?,
    ): PayabliServerException {
        val errorDetails = decodeOrNull(ErrorDetails.serializer(), body)
        return PayabliServerException(
            httpStatus = status,
            reason = errorDetails?.title ?: PayabliServerException.DEFAULT_REASON,
            detail = errorDetails?.detail,
            type = errorDetails?.type,
            instance = errorDetails?.instance,
            rawCode = errorDetails?.rawCode,
            retryAfterMillis = retryAfterMillis,
        )
    }

    /**
     * Resolves `Retry-After` to millis, from either RFC 9110 form. Null when absent or unparseable.
     *
     * `SimpleDateFormat` rather than `java.time`: the API 23 floor has no desugaring. `Locale.US` is
     * mandatory, or a Turkish default locale fails on the month names.
     */
    internal fun retryAfterMillis(
        response: PayabliResponse,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): Long? {
        val raw = response.header(RETRY_AFTER_HEADER)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return if (seconds > MAX_RETRY_AFTER_SECONDS) Long.MAX_VALUE else seconds * MILLIS_PER_SECOND
        }
        HTTP_DATE_FORMATS.forEach { pattern ->
            val parsed =
                runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("GMT") }
                        .parse(raw)
                }.getOrNull()
            if (parsed != null) return (parsed.time - nowMillis()).coerceAtLeast(0L)
        }
        return null
    }

    private fun decline(body: String): PayabliDeclineException {
        val declined = decodeOrNull(DeclineBody.serializer(), body)
        return PayabliDeclineException(
            rawCode = declined?.rawCode,
            reason = declined?.reason ?: PayabliDeclineException.DEFAULT_REASON,
            explanation = declined?.explanation,
            action = declined?.action,
        )
    }

    /**
     * A body that will not decode costs fields, never the classification.
     *
     * `runCatching` is safe here in a way it would not be around a suspending call: this is a pure
     * in-memory decode with no suspension point, so there is no `CancellationException` to swallow.
     */
    private fun <T> decodeOrNull(
        serializer: KSerializer<T>,
        body: String,
    ): T? = runCatching { PayabliJson.format.decodeFromString(serializer, body) }.getOrNull()

    /** A singleton so the default argument costs no allocation per call. */
    private val NO_OVERRIDE: (Int) -> PayabliException? = { null }
}
