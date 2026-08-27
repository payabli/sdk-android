package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliEnvelope
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.attestation.AttestationToken
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Unreserved characters, per RFC 3986 Section 2.3. What an entry point may hold to be one path segment. */
private val PATH_SEGMENT = Regex("^[A-Za-z0-9._~-]+$")

/**
 * The five device-lifecycle calls of `/api/v2/device/taptopay`.
 *
 * **Stateless and orchestration-free by design.** It holds no device identity, persists nothing, keeps no
 * state machine, and does not know that `/challenge` precedes `/attest`. Every value a call needs is a
 * parameter, including the ones that come from a Keystore key this phase does not yet own. That is what makes
 * the sequencing rules — which call follows which, what is cached, what happens when one fails halfway —
 * reviewable in one place, and this is not that place.
 *
 * The call that issues an activation code is absent and stays absent. It is a merchant-side call, the code
 * reaches the device out of band, and an SDK that could issue its own would be an SDK that could activate
 * itself.
 *
 * **Nothing here is wrapped in `Retry`, and that is per route rather than an oversight.** Each of the four
 * POSTs leaves something changed behind it, so a second attempt runs against what the first one already
 * spent. `:core`'s `Retry` is documented as a per-call-site primitive precisely so a call site like this one
 * can decline it. The duplicate-safe unit here is the whole cold sequence, not any single call in it, so
 * retrying belongs to whoever owns the sequence.
 *
 * `/config` is the first route here that would qualify, since it reads and changes nothing, and it is still
 * unwrapped: the assertion it carries is short-lived, so a policy for it is a policy about minting a fresh
 * one, which belongs to the same owner.
 *
 * **Every request here refuses credential recovery, because an attestation is valid only for the credential
 * that obtained it.** A refresh between attesting and using the attestation fails as
 * [DeviceServiceException.NotAttested]. Requests are sent with `isCredentialPinned`, which costs a 401 on
 * these routes its refresh and its replay both: the refresh would rotate the pinned credential out of the
 * binding, and the replay would spend a single-use value a second time.
 *
 * A 2xx envelope decline is not a credential rejection, so the ordinary device failures never reached recovery
 * and are unaffected. Nor does the refusal depend on what these routes answer with today: they report their
 * status inside the envelope, and it holds for the day they stop.
 *
 * **A rotation started by some other capability still breaks the binding**, because one session serves them
 * all. Nothing this client does can prevent that. It costs more than enrollment now that `/config` is here:
 * a rotation between attesting and fetching the credentials leaves a reader that cannot be prepared, and the
 * remedy is to attest again, which the sequence owner drives.
 */
internal class DeviceServiceClient(
    private val transport: PayabliTransport,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Requests a fresh challenge for [entry].
     *
     * The returned value is short-lived and single-use: the first `/attest` that offers it consumes it. It is
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
     * still owed. Registering is **not** the end of the cold path even so — attesting comes next, and
     * activation verifies against what attesting left behind.
     *
     * [hardwareId] identifies the device across calls, so repeating this call with the same one does not add a
     * second device. That makes it the one call in the family that tolerates being repeated, which is a
     * property of the identifier rather than a licence to retry it blindly.
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
     * encoded form: passing the raw one compiles and is refused, with the single-use challenge already spent
     * and the whole sequence needing to restart.
     *
     * [DeviceIdentity.publicKey] is required on this platform even though the wire shape calls it optional:
     * the integrity token does not embed the key, so without it a later assertion cannot be verified against
     * anything.
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
     * [assertion] is short-lived, so it is minted for this call and never reused. How long a code stays good
     * and how many attempts it survives are not enforced or tracked here.
     *
     * Several unrelated refusals arrive as [DeviceServiceException.BadRequest]. Telling them apart needs the
     * refusal's text, which is what [failureMapper] is for.
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
     * The reader credentials for [entry], which only an active device is given.
     *
     * Takes all four assertion headers where `/activate` takes three: this call has no body to carry the
     * device, so the device travels in `X-Device-Id`, which [DeviceAssertion.asHeaders] already sends on both
     * routes.
     *
     * **A device that still owes activation is refused here, and the refusal can arrive either as an envelope
     * decline or as a transport status.** Both become [DeviceServiceException.Forbidden], so a caller branches
     * once, on a classification that is imprecise for the reason that type states.
     *
     * A refusal here is never retried in place. A rejection under [DeviceServiceException.NotAttested] means
     * the credential moved and the binding with it; attesting again from inside a failing call would spend a
     * challenge and hide the rotation that caused it.
     */
    suspend fun config(
        entry: String,
        assertion: DeviceAssertion,
        failureMapper: DeviceFailureMapper = DeviceFailureMapper.None,
    ): ConfigResponse =
        get(
            route = ROUTE_CONFIG,
            path = "$BASE/config/${pathSegment(entry)}",
            payloadSerializer = ConfigResponse.serializer(),
            failureMapper = failureMapper,
            headers = assertion.asHeaders(),
            statusOverride = { statusCode ->
                if (statusCode == HTTP_FORBIDDEN) {
                    // The gateway's refusal carries no service text, and inventing one would put words in
                    // its mouth that a caller could display.
                    DeviceServiceException.Forbidden(statusCode, "")
                } else {
                    null
                }
            },
        )

    /**
     * [entry] as one path segment, or a refusal.
     *
     * A value that is not a single segment is a caller defect, and encoding it sends a request for a
     * paypoint nobody named. `URLEncoder` writes a space as `+`, which is a query-string rule.
     *
     * The message names the field and the shape, never the value, because an entry point identifies a
     * merchant.
     */
    private fun pathSegment(entry: String): String {
        require(PATH_SEGMENT.matches(entry)) {
            "entry must be one path segment of unreserved characters: A-Z a-z 0-9 and . _ ~ -"
        }
        return entry
    }

    /**
     * The four POSTs. Every one of them carries a body and resolves to its own template.
     *
     * The pin is set here and in [get], since the two assemblers build different request shapes. A sixth
     * route inherits it from whichever of them it uses.
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
        // The four POSTs resolve to their own template, because none of them embeds an identifier. Passed
        // anyway, because `route` is the only form the transport may log and defaulting it to null would cost
        // every record in this family the name of the endpoint it came from.
        val request =
            PayabliRequest.json(
                method = HttpMethod.POST,
                path = route,
                body = body,
                bodySerializer = bodySerializer,
                route = route,
                headers = headers,
                isCredentialPinned = true,
            )
        return measured(route) {
            read(
                route = route,
                response = transport.execute(request),
                payloadSerializer = payloadSerializer,
                failureMapper = failureMapper,
                emptyPayload = emptyPayload,
            )
        }
    }

    /**
     * The GET half, whose [path] is **not** its [route].
     *
     * `/config` is the one route here that embeds an identifier, so the two are separate parameters for the
     * first time: [route] is the template the transport records and [path] is the resolved string it sends.
     *
     * No [emptyPayload]. Reaching the response is the answer on `/attest` and `/activate`; here the fields
     * are, so a success carrying none of them is a failure.
     */
    private suspend fun <T> get(
        route: String,
        path: String,
        payloadSerializer: KSerializer<T>,
        failureMapper: DeviceFailureMapper,
        headers: Map<String, String>,
        statusOverride: (Int) -> Throwable?,
    ): T {
        val request =
            PayabliRequest(
                method = HttpMethod.GET,
                path = path,
                route = route,
                headers = headers,
                isCredentialPinned = true,
            )
        return measured(route) {
            read(
                route = route,
                response = transport.execute(request),
                payloadSerializer = payloadSerializer,
                failureMapper = failureMapper,
                statusOverride = statusOverride,
            )
        }
    }

    /**
     * Times one call and reports how it ended.
     *
     * Wrapped around the whole exchange rather than around [read], so a call that never produced a response
     * is counted too: a route unreachable for a minute and a route refusing everything for a minute look
     * identical to a counter that only sees answers.
     *
     * A cancelled call is not reported. It has no outcome of its own, and counting it as a failure would make
     * a screen closed mid-enrollment read as the service refusing.
     */
    private suspend fun <T> measured(
        route: String,
        call: suspend () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            val result = call()
            report(route, TelemetryProperties.Outcome.SUCCEEDED, null, startedAt)
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refusal: DeviceServiceException) {
            report(route, outcomeOf(refusal), refusal.resultCode, startedAt)
            throw refusal
        } catch (failure: PayabliException) {
            report(route, TelemetryProperties.Outcome.FAILED, null, startedAt)
            throw failure
        }
    }

    /**
     * Refused means the far side answered and said no; failed means no usable answer arrived.
     *
     * The distinction is the one an incident asks first, and it is not the same as the status: a refusal
     * arrives inside a 200 on these routes.
     */
    private fun outcomeOf(refusal: DeviceServiceException): String =
        when (refusal) {
            is DeviceServiceException.Undecodable, is DeviceServiceException.ServerFailure ->
                TelemetryProperties.Outcome.FAILED
            else -> TelemetryProperties.Outcome.REFUSED
        }

    private fun report(
        route: String,
        outcome: String,
        resultCode: Int?,
        startedAt: Long,
    ) {
        val event = EVENTS[route] ?: return
        TelemetryRecorders.record(event) {
            buildMap {
                put(TelemetryProperty.OUTCOME.key, outcome)
                put(TelemetryProperty.DURATION_MS.key, elapsedMillis(startedAt).toString())
                resultCode?.let { put(TelemetryProperty.CODE.key, it.toString()) }
            }
        }
    }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    /**
     * The whole of this class's care, once a response exists.
     *
     * The order of the checks is the contract, not a style:
     *
     * 0. [statusOverride] first, so a route can name what a status means to it before the shared table does.
     *    It answers only for statuses the route already treats as failures.
     * 1. `PayabliHttpErrors` next, because a transport failure means the envelope below is not this service
     *    speaking. It is called without a `statusOverride`: these routes put their meaning in the envelope, so
     *    there is no shared status here to give a component reading. It also catches a request refused before
     *    it is read, which answers with a real status and carries no envelope at all.
     * 2. Then the envelope decline, because these routes report a refusal inside a 200 and skipping this step
     *    is exactly how a refusal reads as a success.
     * 3. Only then the payload.
     *
     * Whether an absent `responseData` is usable is the route's business, and it is settled **here** rather
     * than by the caller: [emptyPayload] is what a route substitutes when reaching the response at all is the
     * answer, and a route that leaves it null is saying it cannot proceed without fields. `/attest` and
     * `/activate` supply one; `/challenge`, `/register` and `/config` do not.
     *
     * That policy has to live inside this function, not above it, because the success record is written here.
     * A caller rejecting a null payload afterwards would throw with `device_call_succeeded` already in the log
     * and no failure record beside it, and an incident would read as a success the caller never received.
     */
    private fun <T> read(
        route: String,
        response: PayabliResponse,
        payloadSerializer: KSerializer<T>,
        failureMapper: DeviceFailureMapper,
        emptyPayload: T? = null,
        statusOverride: (Int) -> Throwable? = { null },
    ): T {
        (statusOverride(response.statusCode) ?: PayabliHttpErrors.from(response))?.let { transportFailure ->
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
                // integer a reader would take for a real one. `reason` is never logged: it can quote what was
                // sent.
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
        // and the call would report success for a response that was never sent.
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
                // The supertype stays uncaught. SerializationException extends IllegalArgumentException, so
                // catching that would swallow a genuine programming error raised from inside a serializer,
                // which is why `:core` narrows the same catch.
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
         * Route templates.
         *
         * The first four are also their own paths, because none of them embeds an identifier. [ROUTE_CONFIG]
         * is a template and nothing else: its `{entry}` names a merchant, so the resolved path is not a form
         * anything may record.
         *
         * Visible to the module's tests, which assert the exact string each call goes to. A path is the one
         * part of a request no reviewer can verify by reading the client alone.
         */
        const val ROUTE_CHALLENGE: String = "$BASE/challenge"
        const val ROUTE_REGISTER: String = "$BASE/register"
        const val ROUTE_ATTEST: String = "$BASE/attest"
        const val ROUTE_ACTIVATE: String = "$BASE/activate"
        const val ROUTE_CONFIG: String = "$BASE/config/{entry}"

        /**
         * The event each route is counted under.
         *
         * One name per route rather than one name carrying the route, because a route added here without an
         * event is then a route nobody is counting, and a missing key is easier to see than a missing value.
         */
        private val EVENTS: Map<String, String> =
            mapOf(
                ROUTE_CHALLENGE to TelemetryEvents.TTP_DEVICE_CHALLENGE_COMPLETED,
                ROUTE_REGISTER to TelemetryEvents.TTP_DEVICE_REGISTER_COMPLETED,
                ROUTE_ATTEST to TelemetryEvents.TTP_DEVICE_ATTEST_COMPLETED,
                ROUTE_ACTIVATE to TelemetryEvents.TTP_DEVICE_ACTIVATE_COMPLETED,
                ROUTE_CONFIG to TelemetryEvents.TTP_DEVICE_CONFIG_COMPLETED,
            )
    }
}
