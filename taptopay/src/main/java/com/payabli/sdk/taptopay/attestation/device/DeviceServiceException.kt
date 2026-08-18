package com.payabli.sdk.taptopay.attestation.device

import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * A refusal from one of the device routes.
 *
 * **The transport status is not the verdict.** A refusal arrives inside a successful response, as an envelope
 * carrying `isSuccess: false` and a `resultCode` that reuses HTTP's numbering without being an HTTP status, so
 * a caller that checks the status and stops sees every one of these as a success. `PayabliHttpErrors` still
 * runs first at the call site and raises `PayabliException` for a failure the transport itself reports. The
 * two are disjoint: which one a caller catches says which layer failed.
 *
 * **A request refused before it is read carries no envelope at all**, and this type never describes one.
 * `PayabliHttpErrors` takes that shape and is never consulted on a 2xx, so nothing dispatches between the two;
 * the trap is looking for an envelope in a body that has none.
 *
 * Which field a refusal names does not currently reach the caller: `:core` decodes that part of a 400 into a
 * shape these routes do not send, so it arrives empty. The fix belongs in `:core`, where every 400 in the SDK
 * shares one decode.
 *
 * Device-local rather than new `PayabliErrorCode` cases, on the precedent
 * [com.payabli.sdk.taptopay.attestation.AttestationException] sets and states: that vocabulary is matched
 * string for string by the sibling SDK and is not this module's to widen.
 *
 * **The subtypes are keyed on [resultCode] alone, and no subtype is chosen by matching message text.** A
 * taxonomy built on wording breaks the moment the wording changes. So one result code is one case here, and a
 * caller that needs one split further supplies a [DeviceFailureMapper]: the text-matching lives in the one
 * place that has a reason to accept the risk, and it changes without touching the client.
 *
 * [reason] is server text. It is displayable and **never loggable**, matching how `PayabliException` treats
 * the same field: a message can quote what was sent, so it is kept off `toString` and out of every log field.
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
    /** Never [reason]: it is server text that can quote what was sent. */
    override fun toString(): String = "${javaClass.simpleName}(resultCode=$resultCode)"

    /**
     * The request or the device's state was refused.
     *
     * The widest case: everything that shares this result code lands here, a malformed request included.
     * Splitting it needs `reason`, which is why it is a [DeviceFailureMapper]'s job rather than this class's.
     */
    class BadRequest(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service refused the request", resultCode, reason)

    /**
     * The attestation this call was made against is missing or revoked.
     *
     * **An attestation is valid only for the credential that obtained it**, so this also fires when the host's
     * credential changed between attesting and using it. Either way the remedy is the same and it is not a
     * retry: discard the cached identity and attest again.
     */
    class NotAttested(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device is not attested, or its attestation was revoked", resultCode, reason)

    /**
     * The device or the application is not permitted this call.
     *
     * **A device that owes activation reaches a caller this way**, so this case is not a misconfiguration on
     * its own. What is left under it is not split further: nothing separates those by [resultCode], and a
     * caller needing one of them supplies a [DeviceFailureMapper]. [reason] is empty when the refusal came
     * from the transport rather than from the service.
     *
     * Not retryable.
     */
    class Forbidden(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service forbade the request", resultCode, reason)

    /**
     * The entry point this call names cannot be used for it.
     *
     * A fault in what the host configured, not in the device: the remedy is to correct the entry point or the
     * credential it was paired with, and never to wait for an activation code. [Forbidden] carries the
     * opposite remedy under the same [resultCode], which is what keeps the two apart.
     *
     * Discards nothing, and not retryable.
     */
    class EntryPointUnusable(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service refused the entry point", resultCode, reason)

    /** The entity, paypoint or device named in the request does not exist. */
    class NotFound(
        resultCode: Int?,
        reason: String,
    ) : DeviceServiceException("the device service found nothing for this request", resultCode, reason)

    /**
     * The service failed internally.
     *
     * The only case here worth another attempt, and **not by repeating the call that failed.** A failure can
     * land after the request has already had its effect, so repeating it walks into a refusal for something
     * already spent. The unit to repeat is the whole cold sequence from its first call, which is the same
     * reason nothing in this family is wrapped in `Retry`.
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
     * is needed. Not a refusal by the service, which is why it carries no [resultCode]: the SDK and the
     * service disagree about the contract, and a decline would file that under the service's fault and lose
     * the cause.
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
         * The constants are `HttpURLConnection`'s, as `PayabliHttpErrors` uses for real statuses: the
         * envelope's codes name the same integers with the same meanings, and declaring a second set here
         * would be two names per number and one more place to get one wrong. That these arrive inside a 200
         * does not change what the numbers mean.
         *
         * `>=` on the server bucket rather than an equality, for the reason `PayabliHttpErrors` gives: an
         * unforeseen code in that range is still a failure on the far side, and narrowing it would classify a
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
 * Passed per call rather than held on the client, because the same client serves routes whose refusals mean
 * unrelated things under one code: the mapper that classifies an activation failure has no business
 * inspecting a registration failure. This mirrors the shape the iOS client already uses for the same reason.
 *
 * A mapper is the one place in this package that may read `reason`, and it is the only thing here that breaks
 * when the wording changes.
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
