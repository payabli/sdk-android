package com.payabli.sdk.taptopay.attestation.device

import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * A refusal from one of the `/api/v2/device/taptopay` routes.
 *
 * **These routes answer a business failure with HTTP 200.** The transport status says only that the request
 * reached the service; the refusal lives in the envelope as `isSuccess: false` and a `resultCode` that looks
 * like an HTTP status and is not one. So a caller that checks the status and stops sees every one of these as
 * a success. `PayabliHttpErrors` still runs first at the call site, for the genuine transport failures — a
 * rejected credential, a rate limit, a proxy — and those arrive as `PayabliException`, not as this type. The
 * two are disjoint on purpose: which one a caller catches says which layer failed.
 *
 * **The family has two failure shapes, and this type covers only the second.** A request the service's DTO
 * validation refuses never reaches a controller: it answers with a real HTTP 400 carrying RFC 9457
 * `problem+json` — `{errors, status, title, traceId, type}` — and no envelope. A blank `entry` and a missing
 * `platform` are both that shape. Only a guard inside a controller produces the 200 decline this type
 * describes. `PayabliHttpErrors` takes the first and is never consulted on a 2xx, so nothing dispatches
 * between them; the trap is looking for an envelope in a body that has none.
 *
 * The field names in that `errors` map do not currently reach the caller. `:core` decodes `errors` as a map of
 * `{message, suggestion}` objects and this service sends a map of strings, so the map arrives empty and the one
 * fact worth having — which field was refused — is lost. PLA-2351 fixes it in `:core`, where every 400 in the
 * SDK shares the same decode.
 *
 * Device-local rather than new `PayabliErrorCode` cases, on the precedent
 * [com.payabli.sdk.taptopay.attestation.AttestationException] sets and states: that vocabulary is matched
 * string for string by the sibling SDK and is not this module's to widen.
 *
 * **The subtypes are keyed on [resultCode] alone, and no subtype is chosen by matching message text.** The
 * service currently distinguishes a wrong activation code from a locked-out device from an expired window
 * only by `resultText`, all three under `resultCode` 400, and that error oracle is scheduled for removal. A
 * taxonomy built on those strings would break when it goes. So the 400 bucket stays one case here, and a
 * caller that genuinely needs it split supplies a [DeviceFailureMapper]: the text-matching lives in the one
 * place that has a reason to accept the risk, and it changes without touching the client.
 *
 * [reason] is the server's own text. It is displayable and **never loggable**, matching how
 * `PayabliException` treats the same field: the service echoes request data into some of these messages, so
 * it is kept off `toString` and out of every log field.
 */
internal sealed class DeviceServiceException(
    message: String,
    /**
     * The envelope's `resultCode`, or null when the body carried none.
     *
     * Safe to log — it is a small integer from a fixed set, and `errorCode` is an allowlisted field name.
     */
    val resultCode: Int?,
    val reason: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Never [reason]: it is server text that can echo what was sent. */
    override fun toString(): String = "${javaClass.simpleName}(resultCode=$resultCode)"

    /**
     * The request or the device's state was refused.
     *
     * The widest case, and deliberately so. Everything the activation window can go wrong with lands here —
     * wrong code, five attempts spent, expired code, rejected assertion, a device that was not pending —
     * along with plain malformed input. Splitting them needs `reason`, which is why it is a
     * [DeviceFailureMapper]'s job rather than this class's.
     */
    class BadRequest(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service refused the request", resultCode, reason)

    /**
     * The attestation this call was made against is missing or revoked.
     *
     * The row is keyed on the key alias **and the exact bearer token captured at `/attest`**, so this also
     * fires when the host's credential rotated between attesting and using it. Either way the remedy is the
     * same and it is not a retry: discard the cached identity and attest again.
     */
    class NotAttested(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device is not attested, or its attestation was revoked", resultCode, reason)

    /**
     * The device or the application is not permitted this call.
     *
     * Two distinct conditions the service reports identically: a device that is not yet active, which is the
     * ordinary pending-activation signal, and an application absent from the paypoint's allowlist, which is
     * configuration. Neither is retryable.
     */
    class Forbidden(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service forbade the request", resultCode, reason)

    /** The entity, paypoint or device named in the request does not exist. */
    class NotFound(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service found nothing for this request", resultCode, reason)

    /**
     * The service failed internally.
     *
     * The only case here worth another attempt, and **not by repeating the call that failed.** A 5xx says the
     * service broke somewhere in handling the request, which can be after it has already changed state: a
     * `/attest` that read and deleted its challenge before failing leaves that challenge spent, and an
     * `/activate` can fail having already counted the attempt. Repeating either call then walks into
     * `BadRequest` for a consumed challenge or spends a second of five attempts on a request that never had a
     * chance. The unit to repeat is the whole cold sequence, starting from a new `/challenge`, which is the
     * same reason nothing in this family is wrapped in `Retry`.
     */
    class ServerFailure(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service failed", resultCode, reason)

    /**
     * A refusal carrying a code this SDK does not recognise, or none at all.
     *
     * Present so that an unmapped code is reported as unmapped. Folding it into [BadRequest] would let a
     * future code arrive dressed as a request defect, and a caller would act on a classification nobody
     * made.
     */
    class Unclassified(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service refused the request for an unrecognised reason", resultCode, reason)

    /**
     * The response said success and its body could not be read as one.
     *
     * A missing required field, a payload that is not the shape this route documents, or no payload where one
     * is needed. Not a refusal by the service, which is why it carries no [resultCode]: something between the
     * two of us is wrong about the contract, and treating it as a decline would file it under the service's
     * fault and lose the cause.
     */
    class Undecodable(
        // No default. Nullable because a response can be unusable without anything having thrown — an envelope
        // that never claimed success is one — but every call site knows which case it is in, and a default
        // would let one omit the cause by accident rather than by decision.
        original: Throwable?,
    ) : DeviceServiceException(
            "the device service response could not be decoded",
            null,
            REASON_UNDECODABLE,
            // Wrapped here rather than at the call sites, so no caller can forget. A decoder's own message
            // quotes the input it choked on — kotlinx appends the offending JSON — and a device response body
            // holds a challenge, a challengeId and a deviceId. Redacting `toString` on this class buys nothing
            // if a cause chain underneath it carries the body, and a crash reporter renders the whole chain.
            original?.let { RedactedCause(it) },
        )

    internal companion object {
        /**
         * Not the server's words, because there are none: this case is raised where the body failed to
         * decode, so anything quoted from it would be as untrustworthy as the body.
         */
        const val REASON_UNDECODABLE: String = "The response from the device service could not be read."

        /**
         * The disposition for an envelope decline.
         *
         * The constants are `HttpURLConnection`'s, as `PayabliHttpErrors` uses for real statuses, because the
         * service builds this field by reusing HTTP's numbering rather than inventing a vocabulary. They name
         * the same integers with the same meanings; declaring a second set here would be two names per number
         * and one more place to get one wrong. That these arrive inside a 200 is the envelope's doing and does
         * not change what the numbers mean.
         *
         * `>=` on the server bucket rather than an equality, for the reason `PayabliHttpErrors` gives: an
         * unforeseen code in that range is still the service's own failure, and narrowing it would classify a
         * 503-shaped result as unrecognised.
         */
        fun of(
            resultCode: Int?,
            reason: String,
        ): DeviceServiceException =
            when {
                resultCode == HTTP_BAD_REQUEST -> BadRequest(resultCode, reason)
                resultCode == HTTP_UNAUTHORIZED -> NotAttested(resultCode, reason)
                resultCode == HTTP_FORBIDDEN -> Forbidden(resultCode, reason)
                resultCode == HTTP_NOT_FOUND -> NotFound(resultCode, reason)
                resultCode != null && resultCode >= HTTP_INTERNAL_ERROR -> ServerFailure(resultCode, reason)
                else -> Unclassified(resultCode, reason)
            }
    }
}

/**
 * A cause that keeps a failure's type and stack trace and drops its message.
 *
 * The class name becomes the message, because a type name carries no subject, and the original stack trace is
 * kept because a class, method, file and line are the whole diagnostic value. The chain stops here.
 *
 * `:core` has an identical type for the same reason on the same kind of failure, and it is `internal` to that
 * module, so this is the same rule restated rather than a second rule. If a cross-module fixtures or utility
 * home ever exists, these two collapse into one.
 */
internal class RedactedCause(
    original: Throwable,
) : Exception(original.javaClass.name) {
    init {
        stackTrace = original.stackTrace
    }
}

/**
 * Turns an envelope decline into something more specific than [DeviceServiceException.of] can produce.
 *
 * The extension point for the failures that cannot be told apart by `resultCode`. A mapper returning null
 * defers to the default classification, so one that only cares about a single case handles that case and
 * says nothing about the rest.
 *
 * Passed per call rather than held on the client, because the same client serves routes whose 400s mean
 * unrelated things: the mapper that knows what a wrong activation code looks like has no business inspecting
 * a registration failure. This mirrors the shape the iOS client already uses for the same reason.
 *
 * A mapper is the one place in this package that may read `reason`, and it should expect to be rewritten when
 * the service stops distinguishing its failures by text.
 */
internal fun interface DeviceFailureMapper {
    /** The failure to raise, or null to accept [DeviceServiceException.of]'s classification. */
    fun map(
        resultCode: Int?,
        reason: String,
    ): Throwable?

    companion object {
        /** Defers everything. The default at every call site. */
        val None: DeviceFailureMapper = DeviceFailureMapper { _, _ -> null }
    }
}
