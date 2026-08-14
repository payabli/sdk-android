package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliEnvelope
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.taptopay.attestation.AttestationToken
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * The four device-lifecycle calls of `/api/v2/device/taptopay`.
 *
 * **Stateless and orchestration-free by design.** It holds no device identity, persists nothing, keeps no
 * state machine, and does not know that `/challenge` precedes `/attest`. Every value a call needs is a
 * parameter, including the ones that come from a Keystore key this phase does not yet own. That is what makes
 * the sequencing rules — which call follows which, what is cached, what happens when one fails halfway —
 * reviewable in one place, and this is not that place.
 *
 * `/activate/challenge` is deliberately absent and should stay absent. It is the merchant-side call that
 * mints the six-digit code; the code reaches the device out of band, and an SDK that could mint its own would
 * be an SDK that could activate itself. `/config/{entry}` is absent for a duller reason: its credentials have
 * no consumer until the card-reader work.
 *
 * **Nothing here is wrapped in `Retry`, and that is per route rather than an oversight.** `/attest` consumes
 * the challenge with a delete-on-read, so a second attempt attests against a value the server has already
 * retired; `/activate` counts a failed attempt, so a retry spends one of the five before a lockout;
 * `/challenge` and `/register` both mutate server state as well. `:core`'s `Retry` is documented as a
 * per-call-site primitive precisely so a call site like this one can decline it. The duplicate-safe unit here
 * is the whole cold sequence, not any single call in it, so retrying belongs to whoever owns the sequence.
 *
 * **The server pins the credential, so every request here refuses credential recovery.** The attestation row
 * written at `/attest` records the exact bearer token that made the call, and `/activate` and `/config` require
 * that same one, so a refresh between them fails activation as [DeviceServiceException.NotAttested]. Requests
 * are sent with `isCredentialPinned`, which costs a 401 on these routes its refresh and its replay both: the
 * refresh would rotate the pinned token out of the match, and the replay would spend a single-use challenge or
 * one of the five activation attempts a second time.
 *
 * A 2xx envelope decline is not a credential rejection, so the ordinary device failures never reached recovery
 * and are unaffected. Nor does the refusal describe what these routes answer with today: they report their
 * status inside the envelope, and it holds for the day they stop.
 *
 * **A rotation started by some other capability still breaks the binding**, because one session serves them
 * all. Nothing this client does can prevent that, and it resolves with the facade, which binds a device by its
 * own key rather than by the token that attested it.
 */
internal class DeviceServiceClient(
    private val transport: PayabliTransport,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Requests a fresh challenge for [entry].
     *
     * The returned value has a five-minute life and is consumed by the first `/attest` that offers it. It is
     * not the value Play Integrity signs; see [DeviceAttestationBinding.nonceChallenge].
     */
    suspend fun challenge(
        entry: String,
        failureMapper: DeviceFailureMapper = DeviceFailureMapper.None,
    ): ChallengeResponse =
        post(
            route = ROUTE_CHALLENGE,
            body = ChallengeRequest(entry = entry),
            bodySerializer = ChallengeRequest.serializer(),
            payloadSerializer = ChallengeResponse.serializer(),
            failureMapper = failureMapper,
        )

    /**
     * Registers this device against [entry] and returns the identity every later call uses.
     *
     * A fresh registration comes back pending: [RegisterResponse.isPending] is the signal that activation is
     * still owed. Registering is **not** the end of the cold path even so, because `/attest` accepts a pending
     * device and writes the attestation row that `/activate` verifies against.
     *
     * The server keys its own state machine on [hardwareId], so calling this twice with the same one reuses
     * the pending device rather than creating a second, and an already-active device is superseded by a new
     * record. That makes this the one call in the family that tolerates being repeated — which is a property
     * of the server's handling of [hardwareId], not a licence to retry it blindly.
     */
    suspend fun register(
        entry: String,
        hardwareId: String,
        keyId: String,
        deviceName: String?,
        model: String?,
        osVersion: String?,
        failureMapper: DeviceFailureMapper = DeviceFailureMapper.None,
    ): RegisterResponse =
        post(
            route = ROUTE_REGISTER,
            body =
                RegisterRequest(
                    entry = entry,
                    hardwareId = hardwareId,
                    keyId = keyId,
                    deviceName = deviceName,
                    model = model,
                    osVersion = osVersion,
                    platform = DEVICE_PLATFORM,
                ),
            bodySerializer = RegisterRequest.serializer(),
            payloadSerializer = RegisterResponse.serializer(),
            failureMapper = failureMapper,
        )

    /**
     * Submits the attestation binding [challengeId] to this device's key.
     *
     * [token] is the integrity token as the attestor produced it. The encoding the wire field needs is applied
     * here rather than by the caller, because a `String` parameter cannot tell the compact token from its
     * encoded form: passing the raw one compiles, and the service consumes the single-use challenge in its
     * prerequisite step before the attestation is decoded, so it answers "Attestation is not valid base64"
     * with the challenge already spent and the whole sequence needing to restart.
     *
     * [DeviceIdentity.publicKey] is required on this platform even though the server's own shape calls it
     * optional: the integrity token does not embed the key, so without it the server has nothing to verify a
     * later assertion against.
     *
     * The response body is returned for diagnostics and carries nothing to branch on. Reaching it is the
     * success signal.
     */
    suspend fun attest(
        entry: String,
        challengeId: String,
        identity: DeviceIdentity,
        appId: String,
        token: AttestationToken,
        failureMapper: DeviceFailureMapper = DeviceFailureMapper.None,
    ): AttestResponse =
        post(
            route = ROUTE_ATTEST,
            body =
                AttestRequest(
                    entry = entry,
                    challengeId = challengeId,
                    deviceId = identity.deviceId,
                    keyId = identity.keyId,
                    appId = appId,
                    attestation = DeviceAttestationBinding.attestationField(token),
                    publicKey = identity.publicKey,
                    platform = DEVICE_PLATFORM,
                ),
            bodySerializer = AttestRequest.serializer(),
            payloadSerializer = AttestResponse.serializer(),
            failureMapper = failureMapper,
            // An absent payload is tolerated rather than undecodable: the shipping sibling client discards
            // this body, so a service answering with nothing but `isSuccess: true` is a shape a client has
            // already accepted in production. Demanding fields would refuse a success over a diagnostic.
            emptyPayload = AttestResponse(registered = null, isSandbox = null),
        )

    /**
     * Consumes [activationCode], moving a pending device to active.
     *
     * [assertion] proves possession of the attested key and is verified within a two-minute window, so it is
     * minted for this call and never reused. The code is idempotent inside its own thirty-minute window and
     * the device locks out after five failed attempts; both of those are the server's rules, and neither is
     * enforced or tracked here.
     *
     * A wrong code, a spent lockout, an expired window and a rejected assertion all arrive as
     * [DeviceServiceException.BadRequest]. Telling them apart needs the server's message text, which is what
     * [failureMapper] is for.
     */
    suspend fun activate(
        entry: String,
        deviceId: String,
        activationCode: String,
        assertion: DeviceAssertion,
        failureMapper: DeviceFailureMapper = DeviceFailureMapper.None,
    ): ActivateResponse =
        post(
            route = ROUTE_ACTIVATE,
            body =
                ActivateRequest(
                    entry = entry,
                    deviceId = deviceId,
                    activationCode = activationCode,
                ),
            bodySerializer = ActivateRequest.serializer(),
            payloadSerializer = ActivateResponse.serializer(),
            failureMapper = failureMapper,
            headers = assertion.asHeaders(),
            // Tolerated for the reason given on attest: the sibling client discards this one too.
            emptyPayload = ActivateResponse(deviceId = null, status = null),
        )

    /**
     * One POST, and the whole of this class's care.
     *
     * The order of the three checks is the contract, not a style:
     *
     * 1. `PayabliHttpErrors` first, because a transport failure means the envelope below is not this service
     *    speaking. It is called without a `statusOverride`: these routes put their meaning in the envelope, so
     *    there is no shared status here to give a component reading. It also catches the failures that never
     *    reach a controller: DTO validation answers with a real 400 and `problem+json`, carrying no envelope.
     *    A missing `platform` is one of those.
     * 2. Then the envelope decline, because these routes report a refusal as HTTP 200 and skipping this step
     *    is exactly how a refusal reads as a success.
     * 3. Only then the payload.
     *
     * Whether an absent `responseData` is usable is the route's business, and it is settled **here** rather
     * than by the caller: [emptyPayload] is what a route substitutes when reaching the response at all is the
     * answer, and a route that leaves it null is saying it cannot proceed without fields. `/attest` and
     * `/activate` supply one; `/challenge` and `/register` do not.
     *
     * That policy has to live inside this function, not above it, because the success record is written here.
     * A caller rejecting a null payload afterwards would throw with `device_call_succeeded` already in the log
     * and no failure record beside it, and an incident would read as a success the caller never received.
     */
    private suspend fun <B, T> post(
        route: String,
        body: B,
        bodySerializer: KSerializer<B>,
        payloadSerializer: KSerializer<T>,
        failureMapper: DeviceFailureMapper,
        headers: Map<String, String> = emptyMap(),
        emptyPayload: T? = null,
    ): T {
        // route and path are the same string for all four: none of them embeds an identifier. Passed anyway,
        // because `route` is the only form the transport may log and defaulting it to null would cost every
        // record in this family the name of the endpoint it came from.
        val request =
            PayabliRequest.json(
                method = HttpMethod.POST,
                path = route,
                body = body,
                bodySerializer = bodySerializer,
                route = route,
                headers = headers,
                // One place for all four, so a fifth route added to this class inherits it.
                isCredentialPinned = true,
            )
        val response = transport.execute(request)
        PayabliHttpErrors.from(response)?.let { transportFailure ->
            logger.warn(
                LogField.safe("event", "device_call_failed"),
                LogField.safe("route", route),
                LogField.safe("statusCode", response.statusCode),
            ) { "the device service call failed at the transport" }
            throw transportFailure
        }
        PayabliEnvelope.declineOutcome(response.body)?.let { declined ->
            logger.warn(
                LogField.safe("event", "device_call_declined"),
                LogField.safe("route", route),
                // As a string so a decline that carried no code records as null rather than as a stand-in
                // integer a reader would take for a real one. `reason` is never logged: the service echoes
                // request data into some of these messages.
                LogField.safe("errorCode", declined.code?.toString()),
            ) { "the device service declined the call" }
            throw failureMapper.map(declined.code, declined.reason)
                ?: DeviceServiceException.of(declined.code, declined.reason)
        }
        val body = response.bodyAsText()
        // Success has to be *claimed*, not merely not-denied. `declineOutcome` above reads an absent or null
        // `isSuccess` as "not a decline" and returns null, and `Success` does not model the field, so without
        // this a body of `{}` behind a 200 walks through both checks and reaches the payload decode. On
        // `/challenge` and `/register` that surfaces anyway, because their required fields are missing; on
        // `/attest` and `/activate` it would not, because both substitute an empty ack for an absent payload,
        // and the call would report success for a response the service never sent.
        val claimed =
            try {
                PayabliJson.format.decodeFromString(PayabliEnvelope.Status.serializer(), body)
            } catch (failure: SerializationException) {
                // Not `runCatching`, which catches Throwable: an OutOfMemoryError raised mid-decode would come
                // back as "the service sent a bad response", blaming the input for a process-fatal condition.
                // Same boundary as the payload decode below, as `:core` draws it, and as
                // `AttestationChallenge.classic` spells out for exactly this trap.
                throw undecodable(route, response.statusCode, failure)
            }
        if (claimed.isSuccess != true) {
            throw undecodable(route, response.statusCode, null)
        }
        val payload =
            try {
                PayabliJson.format
                    .decodeFromString(PayabliEnvelope.Success.serializer(payloadSerializer), body)
                    .responseData
            } catch (failure: SerializationException) {
                // The supertype is deliberately not caught. SerializationException extends
                // IllegalArgumentException, so catching that would swallow a genuine programming error raised
                // from inside a serializer, which is the reason `:core` narrows the same catch.
                throw undecodable(route, response.statusCode, failure)
            }
        val resolved = payload ?: emptyPayload ?: throw undecodable(route, response.statusCode, null)
        logger.debug(
            LogField.safe("event", "device_call_succeeded"),
            LogField.safe("route", route),
            LogField.safe("statusCode", response.statusCode),
        ) { "the device service call succeeded" }
        return resolved
    }

    /**
     * Logs an unusable response and builds the failure for it.
     *
     * One place, because both ways a 2xx can be unusable — an envelope that never claimed success, and a
     * payload that would not decode — are the same finding for a caller and should read identically in a log.
     * [Undecodable][DeviceServiceException.Undecodable] redacts [cause] itself.
     */
    private fun undecodable(
        route: String,
        statusCode: Int,
        cause: Throwable?,
    ): DeviceServiceException {
        logger.warn(
            LogField.safe("event", "device_response_undecodable"),
            LogField.safe("route", route),
            LogField.safe("statusCode", statusCode),
        ) { "the device service response could not be decoded" }
        return DeviceServiceException.Undecodable(cause)
    }

    internal companion object {
        private const val BASE = "/api/v2/device/taptopay"

        /**
         * Route templates, which for these four are also the paths: none embeds an identifier.
         *
         * Visible to the module's tests, which assert the exact string each call goes to. A path is the one
         * part of a request no reviewer can verify by reading the client alone.
         */
        const val ROUTE_CHALLENGE: String = "$BASE/challenge"
        const val ROUTE_REGISTER: String = "$BASE/register"
        const val ROUTE_ATTEST: String = "$BASE/attest"
        const val ROUTE_ACTIVATE: String = "$BASE/activate"
    }
}
