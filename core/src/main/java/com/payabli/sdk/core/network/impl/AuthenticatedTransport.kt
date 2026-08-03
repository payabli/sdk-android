package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.AuthRecoveryPolicy
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

private const val HTTP_UNAUTHORIZED = 401

/**
 * Runs [AuthRecoveryPolicy]: one 401, one refresh, one replay, second 401 terminal. Mechanism only, so what
 * counts as a rejection and what a surviving one becomes are the policy's.
 *
 * It does not inject the bearer; `BearerDecoration` does, inside [base]'s chain. So a forgotten wrapper costs
 * a recovery, never authentication.
 *
 * **What may be replayed is this class's decision, not the policy's**, because the policy is `open` and a
 * subclass could otherwise widen its way into a double charge. A replay needs one of two arguments:
 *
 * - the response was **401**, refused before processing, so replaying even a POST cannot double-charge; or
 * - the method is **idempotent** (RFC 9110 Section 9.2.2: `PUT`, `DELETE` and the safe methods), so a second
 *   identical request has the effect of one.
 *
 * A widened policy on a `POST` or `PATCH` satisfies neither, so the refresh still happens and the original
 * response is returned unreplayed. That keeps [AuthRecoveryPolicy] useful for a capability's own routes
 * without letting it authorize a replay the status does not justify.
 *
 * **It only sees what arrives as an HTTP 401.** Card-present device routes answer 200 with the status in the
 * envelope, so this never fires there, which is correct: those routes pin the token captured at attestation
 * and a refresh would rotate it out of the match. Widen or narrow the policy deliberately, never rely on the
 * status happening not to be 401.
 */
internal class AuthenticatedTransport(
    private val base: PayabliTransport,
    private val auth: PayabliAuth,
    private val recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
    /** [LogCategory.NETWORK], not `AUTH`: declining a replay is this layer's decision, not the holder's. */
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
    /** Told only when auth is finished for good. See [AuthFailureListener] for why it is told rather than inferred. */
    private val onAuthFailure: AuthFailureListener = AuthFailureListener { },
) : PayabliTransport {
    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        val stamped = SentToken()
        val first = withContext(stamped) { base.execute(request) }
        if (!recovery.isCredentialRejection(first)) return first

        // The token the chain stamped, not one this class read earlier: a rotation between the two reads would
        // make them disagree, and reporting the earlier one replays the credential just refused.
        refresh(stamped.value ?: auth.accessToken())

        // Refresh first, then decide: a rejected credential is worth replacing whether or not this particular
        // request may be sent again, so the next one starts clean.
        if (!mayReplay(request, first)) {
            logger.warn(
                LogField.safe("method", request.method.wireName),
                LogField.safe("statusCode", first.statusCode),
                routeField(request),
            ) { "replay declined: the method is not idempotent and the status was not 401" }
            return first
        }

        // No re-authorizing: re-entering the transport re-runs the chain, which reads the token again.
        val second = withContext(SentToken()) { base.execute(request) }
        if (!recovery.isCredentialRejection(second)) return second

        // A token minted seconds ago and refused again is an authorization fact, not a transient one, which
        // is the same reason AuthRecoveryPolicy.exhausted is not open. Nothing further inside the SDK can fix
        // it, so the session hears about it before the caller does.
        val exhausted = recovery.exhausted()
        onAuthFailure.onUnrecoverable(exhausted)
        throw exhausted
    }

    /**
     * Refreshes, and reports the session dead only when the failure is structural.
     *
     * The discriminator is [PayabliAuth.canRefresh], not the error: a provider that timed out or threw once
     * is a bad minute for the host's backend and the next request may well succeed, whereas no provider at
     * all means every future refresh fails identically. Condemning a session for the first would make
     * [SdkState.ReinitializeRequired] fire on a transient blip and teach hosts to ignore it.
     */
    private suspend fun refresh(rejected: String) {
        try {
            auth.invalidateAndRefresh(rejected)
        } catch (failure: PayabliException) {
            if (!auth.canRefresh) onAuthFailure.onUnrecoverable(failure)
            throw failure
        }
    }

    /**
     * Whether sending [request] a second time is defensible after [rejected].
     *
     * Deliberately not on [AuthRecoveryPolicy]: an overridable hook here would let the same subclass that
     * widened the rejection also widen the replay, which is the hazard rather than the guard.
     */
    private fun mayReplay(
        request: PayabliRequest,
        rejected: PayabliResponse,
    ): Boolean = rejected.statusCode == HTTP_UNAUTHORIZED || request.method.isIdempotent

    /** The template only. [PayabliRequest.path] is resolved and may embed an identifier. */
    private fun routeField(request: PayabliRequest): LogField =
        request.route?.let { LogField.safe("route", it) } ?: LogField.redacted("route", null)

    /** Not delegated: [base]'s overload maps a 401 to a typed error before this could recover it. */
    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = execute(request).asV2Envelope(payloadSerializer)
}

/**
 * RFC 9110 Section 9.2.2: "Of the request methods defined by this specification, PUT, DELETE, and safe
 * request methods are idempotent", with Section 9.2.1 naming GET, HEAD, OPTIONS and TRACE as safe.
 *
 * So `POST` and `PATCH` are excluded, and `PATCH` specifically: it looks like a sibling of `PUT` and is not
 * one. Private rather than a member of [HttpMethod], because one call site needs it and a public property on
 * a shared enum would owe a parity disposition for no gain.
 */
private val HttpMethod.isIdempotent: Boolean
    get() =
        when (this) {
            HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE -> true
            HttpMethod.POST, HttpMethod.PATCH -> false
        }
