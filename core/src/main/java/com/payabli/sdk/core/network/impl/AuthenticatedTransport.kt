package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.AuthRecoveryPolicy
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * Runs [AuthRecoveryPolicy]: one 401, one refresh, one replay, second 401 terminal. Mechanism only, so
 * what counts as a rejection is the policy's and what may be replayed is this class's, because the policy
 * is `open`. The bearer is `BearerDecoration`'s inside [base]'s chain; a pinned request is read here.
 *
 * @property logger where the replay and pin decisions are logged, [LogCategory.NETWORK] because they are this layer's.
 * @property onAuthFailure told only when auth is finished for good. See [AuthFailureListener] for why it is told rather than inferred.
 */
internal class AuthenticatedTransport(
    private val base: PayabliTransport,
    private val auth: PayabliAuth,
    private val recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
    private val onAuthFailure: AuthFailureListener = AuthFailureListener { },
) : PayabliTransport {
    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        // Fail without a round trip on an instance already known to be finished. This is a convenience, not
        // the guard: [PayabliAuth] refuses a refresh claim on its own, so a request that slips past this
        // line still cannot reach the host's broker.
        auth.terminalFailure?.let { throw PayabliGenericException(it.type, it.reason) }

        val stamped = SentToken()
        val first = withContext(stamped) { base.execute(request) }
        if (!recovery.isCredentialRejection(first)) return first

        // Above the refresh, which is the half that breaks a pinned credential. Declining only the replay
        // would still rotate the token the service is holding the request to.
        if (request.isCredentialPinned) {
            logger.warn(
                LogField.safe("method", request.method.wireName),
                LogField.safe("statusCode", first.statusCode),
                routeField(request),
            ) { "recovery declined: the route pins the credential it was sent" }
            return first
        }

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
        val replayed = SentToken()
        val second = withContext(replayed) { base.execute(request) }
        if (!recovery.isCredentialRejection(second)) return second

        // A token minted seconds ago and refused again is an authorization fact, not a transient one, which
        // is the same reason AuthRecoveryPolicy.exhausted is not open. Nothing further inside the SDK can fix
        // it, so the session hears about it before the caller does.
        //
        // Unless the token has moved on, or is moving on. Another caller can replace it between this attempt
        // leaving and its rejection arriving, and can also be midway through replacing it right now, in
        // which case a refresh that may well succeed is already running. Either way the rejection is
        // evidence about a token rather than about the session, and acting on it would end a session whose
        // next token works. `PayabliAuth` answers both questions and records the outcome in one lock
        // acquisition, so no refresh can start in the gap a check-then-act would leave open.
        val exhausted = recovery.exhausted()
        val sent = replayed.value
        val finished =
            // Nothing stamped means the chain did not run, which is not a state to be lenient about.
            if (sent == null) {
                auth.finish(exhausted)
                true
            } else {
                auth.finishIfSettledOn(sent, exhausted)
            }

        // Outside the lock: telling the session runs a listener, and a listener is foreign code.
        if (finished) onAuthFailure.onUnrecoverable(exhausted)
        throw exhausted
    }

    /**
     * Refreshes, and never finishes the session on the outcome: a provider that timed out or threw is a
     * transient blip, and finishing here would teach hosts to ignore [SdkState.ReinitializeRequired]. What
     * finishes a session is a refreshed token refused in turn, which [PayabliAuth.finishIfSettledOn] decides.
     *
     * @param rejected the token the chain stamped and the service refused.
     */
    private suspend fun refresh(rejected: String) {
        auth.invalidateAndRefresh(rejected)
    }

    /**
     * Whether sending [request] a second time is defensible after [rejected]: the status was 401, refused
     * before processing, or the method is idempotent (RFC 9110 Section 9.2.2). This is this class's, not
     * the `open` policy's, so a subclass cannot widen the replay with the rejection.
     *
     * @param request the refused request, whose method decides alongside the status.
     * @param rejected the refusal the replay would answer.
     * @return true only when the status was 401 or the method is idempotent; false otherwise.
     */
    private fun mayReplay(
        request: PayabliRequest,
        rejected: PayabliResponse,
    ): Boolean = rejected.statusCode == HTTP_UNAUTHORIZED || request.method.isIdempotent

    /**
     * The template only. [PayabliRequest.path] is resolved and may embed an identifier.
     *
     * @param request the request whose route template is logged.
     * @return a safe field carrying the template, or a redacted one when the request carried none.
     */
    private fun routeField(request: PayabliRequest): LogField =
        request.route?.let { LogField.safe("route", it) } ?: LogField.redacted("route", null)

    /**
     * Not delegated: [base]'s overload maps a 401 to a typed error before this could recover it.
     *
     * @param request the request sent through the wrapped transport.
     * @param payloadSerializer the generated serializer the answer's payload decodes through.
     * @return the wrapped transport's answer, decoded into the v2 envelope.
     */
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
