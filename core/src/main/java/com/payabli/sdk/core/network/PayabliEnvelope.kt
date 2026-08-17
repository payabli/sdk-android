// IDE-only workaround, not a compiler requirement: K2's IDE analysis flags the plugin-generated
// $serializer as needing this opt-in, while the compiler exempts it (KTIJ-31549). Remove when fixed.
@file:OptIn(InternalSerializationApi::class)

package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The legacy `isSuccess` / `responseData` envelope, used by the device routes.
 *
 * A refusal arrives inside a 200, as `isSuccess: false` with the reason in `responseData`, so a caller peeks
 * at [Status] before committing to a full decode:
 *
 * ```kotlin
 * val outcome = PayabliEnvelope.declineOutcome(body)
 * if (outcome != null) { /* decline path */ } else { /* decode Success<MyPayload> */ }
 * ```
 *
 * The shapes are nested rather than top-level because `Status` and `Success` would be far too generic
 * as top-level names, and none of them is meaningful outside this envelope.
 *
 * Every field is optional, so a malformed body reads as "not a decline" rather than throwing.
 * [PayabliV2Envelope] differs: there, a missing `code` is a decode failure.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliEnvelope {
    /** Thin peek: just enough to choose between the success and decline decodes. */
    @Serializable
    public class Status(
        public val isSuccess: Boolean? = null,
        public val responseText: String? = null,
    )

    /** The `responseData` contents on a decline. */
    @Serializable
    public class DeclinePayload(
        public val resultCode: Int? = null,
        public val resultText: String? = null,
    )

    /** Pulls [DeclinePayload] out of `responseData` when `isSuccess` is false. */
    @Serializable
    public class DeclineEnvelope(
        public val responseData: DeclinePayload? = null,
    )

    /** Pulls the endpoint-specific payload out of `responseData` when `isSuccess` is true. */
    @Serializable
    public class Success<T>(
        public val responseData: T? = null,
    )

    /** Payload placeholder for endpoints that return only `isSuccess: true`. */
    @Serializable
    public class EmptyPayload

    /** A decoded decline. [reason] is always non-empty; [code] is absent on a partial body. */
    public class DeclineOutcome(
        public val code: Int?,
        public val reason: String,
    ) {
        /** [reason] is server text and may echo request data, so it is not in [toString]. */
        override fun toString(): String = "DeclineOutcome(code=$code)"
    }

    /**
     * Returns the decline when [body] is an HTTP 200 decline, or null when it looks like a success.
     *
     * Malformed input returns null rather than throwing: the caller's subsequent [Success] decode is
     * what produces the canonical decode error, so failures surface in one place.
     */
    public fun declineOutcome(body: ByteArray): DeclineOutcome? = declineOutcome(body.toString(Charsets.UTF_8))

    /** String overload of [declineOutcome]. */
    public fun declineOutcome(body: String): DeclineOutcome? {
        val status = PayabliJson.decodeOrNull(Status.serializer(), body)
        if (status?.isSuccess != false) return null
        val declined = PayabliJson.decodeOrNull(DeclineEnvelope.serializer(), body)?.responseData
        return DeclineOutcome(
            code = declined?.resultCode,
            reason = declined?.resultText ?: status.responseText ?: DEFAULT_DECLINE_REASON,
        )
    }

    private const val DEFAULT_DECLINE_REASON = "server declined"
}
