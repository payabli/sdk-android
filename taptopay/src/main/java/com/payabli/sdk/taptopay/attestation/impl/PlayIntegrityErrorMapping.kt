package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.VerdictClass

/**
 * Turns a platform error code into the disposition a caller can act on.
 *
 * **Two tables, not one, and the reason is a collision rather than tidiness.** `-17` is
 * `CLIENT_TRANSIENT_ERROR` for a classic request and `REQUEST_HASH_TOO_LONG` for a standard one: one is
 * "wait and try again", the other is "this SDK built the request wrong". A single table would have to pick
 * one, and would be wrong half the time in a way nothing would ever surface.
 *
 * Both constant sets are Java annotation constants, so they inline at compile time and nothing here loads
 * an Android class. That is what keeps this file, and its test, on the JVM.
 *
 * **`TOO_MANY_REQUESTS` is reported, and only from here.** It is the one code whose cause is not the device
 * in front of you: the request budget belongs to the cloud project and is shared by every app embedding this
 * SDK, so each device sees only its own failure while the cause is fleet-wide. Telling one device retrying
 * from the budget being gone takes a count across devices, which is why it is reported rather than logged.
 *
 * `ThrottleGate` refuses locally with the same disposition and the same code without calling the platform,
 * and those refusals do not reach this file. That is what keeps one platform throttle plus twenty local
 * refusals from counting as twenty-one. The two look alike to a caller, whose action is identical either
 * way; they are not interchangeable to a counter.
 */
internal object PlayIntegrityErrorMapping {
    /** Which request shape met the limit. The two have separate tables, so a count needs to say which. */
    private const val SHAPE_STANDARD = "standard"
    private const val SHAPE_CLASSIC = "classic"

    /**
     * The disposition for [errorCode] as reported by a [verdictClass] request.
     *
     * A null code means the platform failed without reporting one, which lands in the same place an
     * unrecognised code does.
     */
    fun failureFor(
        errorCode: Int?,
        verdictClass: VerdictClass,
        cause: Throwable? = null,
    ): AttestationException =
        when (verdictClass) {
            VerdictClass.STANDARD -> standard(errorCode, cause)
            VerdictClass.CLASSIC -> classic(errorCode, cause)
        }

    private fun standard(
        code: Int?,
        cause: Throwable?,
    ): AttestationException =
        when (code) {
            StandardIntegrityErrorCode.NETWORK_ERROR,
            StandardIntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            StandardIntegrityErrorCode.INTERNAL_ERROR,
            ->
                AttestationException.Retryable(code, cause)

            // Reaching here means the attestor already discarded its provider and prepared a fresh one,
            // and the fresh one was called invalid too. Nothing left to do locally but try again later.
            StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID ->
                AttestationException.Retryable(code, cause)

            StandardIntegrityErrorCode.API_NOT_AVAILABLE,
            StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            StandardIntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            StandardIntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
            StandardIntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            ->
                AttestationException.RemediationRequired(code, cause)

            StandardIntegrityErrorCode.APP_NOT_INSTALLED,
            StandardIntegrityErrorCode.APP_UID_MISMATCH,
            ->
                AttestationException.IntegrityFailed(code, cause)

            StandardIntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            StandardIntegrityErrorCode.REQUEST_HASH_TOO_LONG,
            ->
                AttestationException.Misconfigured(code, cause)

            StandardIntegrityErrorCode.TOO_MANY_REQUESTS -> throttled(code, cause, SHAPE_STANDARD)

            else -> unrecognised(code, cause)
        }

    private fun classic(
        code: Int?,
        cause: Throwable?,
    ): AttestationException =
        when (code) {
            IntegrityErrorCode.NETWORK_ERROR,
            IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            IntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            IntegrityErrorCode.INTERNAL_ERROR,
            ->
                AttestationException.Retryable(code, cause)

            IntegrityErrorCode.API_NOT_AVAILABLE,
            IntegrityErrorCode.PLAY_STORE_NOT_FOUND,
            IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND,
            IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
            IntegrityErrorCode.CANNOT_BIND_TO_SERVICE,
            IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED,
            IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED,
            ->
                AttestationException.RemediationRequired(code, cause)

            IntegrityErrorCode.APP_NOT_INSTALLED,
            IntegrityErrorCode.APP_UID_MISMATCH,
            ->
                AttestationException.IntegrityFailed(code, cause)

            // The three nonce complaints are here rather than under remediation because the nonce is
            // this SDK's to get right. They should be unreachable: a challenge that fails any of the
            // three cannot be constructed. Arriving anyway means this SDK's validation and the platform's
            // disagree, which is a defect to surface rather than to retry into the same answer.
            IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID,
            IntegrityErrorCode.NONCE_TOO_SHORT,
            IntegrityErrorCode.NONCE_TOO_LONG,
            IntegrityErrorCode.NONCE_IS_NOT_BASE64,
            ->
                AttestationException.Misconfigured(code, cause)

            IntegrityErrorCode.TOO_MANY_REQUESTS -> throttled(code, cause, SHAPE_CLASSIC)

            else -> unrecognised(code, cause)
        }

    /**
     * The throttle disposition, reported on the way out.
     *
     * [requestShape] is carried because the two request shapes have separate tables and a reader needs to
     * know which one met the limit.
     */
    private fun throttled(
        code: Int,
        cause: Throwable?,
        requestShape: String,
    ): AttestationException {
        TelemetryRecorders.record(TelemetryEvents.TTP_ATTESTATION_QUOTA_EXHAUSTED) {
            mapOf(
                TelemetryProperty.REASON.key to requestShape,
                TelemetryProperty.CODE.key to code.toString(),
            )
        }
        return AttestationException.Throttled(code, cause)
    }

    /**
     * A code neither table knows, a failure the platform reported without one, and the platform's own
     * "no error" arriving as a failure.
     *
     * Retryable rather than a hard failure. The platform adds codes over releases, and a new
     * one is far more likely to be a new transient condition than a new verdict; classifying the unknown as
     * a failed integrity check would turn the next such addition into a decline. Nothing is weakened by
     * this, because retrying never produces a token that was not issued: the caller either eventually gets
     * one or never does.
     */
    private fun unrecognised(
        code: Int?,
        cause: Throwable?,
    ): AttestationException = AttestationException.Retryable(code, cause)
}
