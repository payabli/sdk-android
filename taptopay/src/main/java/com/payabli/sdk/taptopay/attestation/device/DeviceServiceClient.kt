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
import kotlinx.serialization.KSerializer

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
 * **Two things about the credential, both inherited and neither fixable here.** The attestation row the server
 * writes at `/attest` pins the exact bearer token that made that call, and `/activate` and `/config` require
 * the same one, so a token refresh in between fails activation as [DeviceServiceException.NotAttested]. And
 * the transport's own credential recovery replays only idempotent methods, which excludes every call here, so
 * a rejected credential surfaces rather than being retried behind our back. This client must not work around
 * either: narrowing the recovery policy so a refresh cannot break the binding at all is separate work.
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
        ) ?: throw DeviceServiceException.Undecodable()

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
        ) ?: throw DeviceServiceException.Undecodable()

    /**
     * Submits the attestation binding [challengeId] to this device's key.
     *
     * [attestation] is the encoded integrity token, not the token itself
     * ([DeviceAttestationBinding.attestationField]), and [publicKey] is required on this platform even though
     * the server's own shape calls it optional: the integrity token does not embed the key, so without it the
     * server has nothing to verify a later assertion against.
     *
     * The response body is returned for diagnostics and carries nothing to branch on. Reaching it is the
     * success signal.
     */
    suspend fun attest(
        entry: String,
        challengeId: String,
        deviceId: String,
        keyId: String,
        appId: String,
        attestation: String,
        publicKey: String,
        failureMapper: DeviceFailureMapper = DeviceFailureMapper.None,
    ): AttestResponse {
        val payload =
            post(
                route = ROUTE_ATTEST,
                body =
                    AttestRequest(
                        entry = entry,
                        challengeId = challengeId,
                        deviceId = deviceId,
                        keyId = keyId,
                        appId = appId,
                        attestation = attestation,
                        publicKey = publicKey,
                        platform = DEVICE_PLATFORM,
                    ),
                bodySerializer = AttestRequest.serializer(),
                payloadSerializer = AttestResponse.serializer(),
                failureMapper = failureMapper,
            )
        // An absent payload is tolerated rather than undecodable: the shipping sibling client discards this
        // body, so a service answering with nothing but `isSuccess: true` is a shape a client has already
        // accepted in production. Demanding fields here would refuse a success over a diagnostic.
        return payload ?: AttestResponse(registered = null, isSandbox = null)
    }

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
    ): ActivateResponse {
        val payload =
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
            )
        // Tolerated for the reason given on attest: the sibling client discards this one too.
        return payload ?: ActivateResponse(deviceId = null, status = null)
    }

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
     * Returns null when the response was a success carrying no `responseData`, leaving each route to say
     * whether that is usable: `/challenge` and `/register` need their fields and treat it as undecodable,
     * while `/attest` and `/activate` substitute an empty one because reaching them at all is the answer.
     */
    private suspend fun <B, T> post(
        route: String,
        body: B,
        bodySerializer: KSerializer<B>,
        payloadSerializer: KSerializer<T>,
        failureMapper: DeviceFailureMapper,
        headers: Map<String, String> = emptyMap(),
    ): T? {
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
        val payload =
            try {
                PayabliJson.format
                    .decodeFromString(PayabliEnvelope.Success.serializer(payloadSerializer), response.bodyAsText())
                    .responseData
            } catch (undecodable: Exception) {
                // The decoder's failures are all SerializationException, and an IllegalArgumentException can
                // reach here from a malformed structure. Exception rather than Throwable, so a JVM Error is
                // not re-reported as a contract mismatch.
                logger.warn(
                    LogField.safe("event", "device_response_undecodable"),
                    LogField.safe("route", route),
                    LogField.safe("statusCode", response.statusCode),
                ) { "the device service response could not be decoded" }
                throw DeviceServiceException.Undecodable(undecodable)
            }
        logger.debug(
            LogField.safe("event", "device_call_succeeded"),
            LogField.safe("route", route),
            LogField.safe("statusCode", response.statusCode),
        ) { "the device service call succeeded" }
        return payload
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
