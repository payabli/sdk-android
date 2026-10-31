package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliEnvelope
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PercentEncoding
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.attestation.AttestationToken
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Unreserved characters, per RFC 3986 Section 2.3. What an entry point may hold to be one path segment. */
private val PATH_SEGMENT = Regex("^[A-Za-z0-9._~-]+$")

/**
 * The device-lifecycle calls. Stateless: which call follows which, and any retry, belong to the caller, since each
 * POST spends something. Every request is credential-pinned, because an attestation holds only for the credential
 * that obtained it, so a 401 is never refreshed or replayed.
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
            body = ChallengeRequest(entry = entry, platform = DEVICE_PLATFORM),
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
            // An absent payload is tolerated rather than undecodable: a service answering with nothing but
            // `isSuccess: true` has succeeded, and demanding fields would refuse a success over a diagnostic.
            emptyPayload = AttestResponse(registered = null, isSandbox = null),
        )

    /**
     * Consumes [activationCode], moving a pending device to active.
     *
     * [assertion] is short-lived, so it is minted for this call and never reused. How long a code stays good
     * and how many attempts it survives are not enforced or tracked here.
     *
     * Several unrelated refusals share a status. Telling them apart needs the refusal's text, which is what
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
            // Tolerated for the reason given on attest.
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
     * decline or as a transport status with no body.** Both become [DeviceServiceException.Forbidden], so a caller
     * branches once, on a classification that is imprecise for the reason that type states. A refusal the
     * service worded reaches [failureMapper] first, at either status.
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
            path = PercentEncoding.pathFrom(ROUTE_CONFIG, pathSegment(entry)),
            payloadSerializer = ConfigResponse.serializer(),
            failureMapper = failureMapper,
            headers = assertion.asHeaders(),
            statusOverride = { statusCode ->
                if (statusCode == HTTP_FORBIDDEN) {
                    // Reached only by a 403 with no envelope declining under it: the gateway's refusal carries
                    // no service text, and inventing one would put words in its mouth that a caller could display.
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
                refusesAtStatus = true,
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
        } catch (refusal: DeviceActivationException) {
            // `/activate` is the one route whose caller maps refusals to its own type, which extends neither
            // of the two below. Without this the only route that can refuse a payer's code reported nothing
            // when it did.
            report(route, TelemetryProperties.Outcome.REFUSED, refusal.resultCode, startedAt)
            throw refusal
        } catch (failure: PayabliException) {
            report(route, TelemetryProperties.Outcome.FAILED, null, startedAt)
            throw failure
        }
    }

    /** Refused means the far side answered and said no; failed means no usable answer arrived. */
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
     * Order is the contract. A 4xx declining under its own code is the service refusing when [refusesAtStatus] is set
     * or [statusOverride] claims that status; with [refusesAtStatus] an unenveloped 400 is this SDK's defect; otherwise
     * the status decides. Then the decline, then the payload. [emptyPayload] is settled here, never by the caller.
     */
    private fun <T> read(
        route: String,
        response: PayabliResponse,
        payloadSerializer: KSerializer<T>,
        failureMapper: DeviceFailureMapper,
        emptyPayload: T? = null,
        refusesAtStatus: Boolean = false,
        statusOverride: (Int) -> Throwable? = { null },
    ): T {
        val declineOutcome = PayabliEnvelope.declineOutcome(response.body)
        val status = response.statusCode
        val serviceRefused =
            status in HTTP_BAD_REQUEST until HTTP_INTERNAL_ERROR &&
                declineOutcome?.code == status &&
                (refusesAtStatus || statusOverride(status) != null)
        val transportFailure =
            when {
                serviceRefused -> null
                refusesAtStatus && status == HTTP_BAD_REQUEST && declineOutcome == null ->
                    DeviceServiceException.BadRequest(status, PayabliHttpErrors.from(response)?.reason.orEmpty())
                else -> statusOverride(status) ?: pinnedRefusalOf(PayabliHttpErrors.from(response), status)
            }
        transportFailure?.let {
            logger.warn(
                LogField.safe("event", "device_call_failed"),
                LogField.safe("route", route),
                LogField.safe("statusCode", response.statusCode),
            ) { "the device service call failed at the transport" }
            throw it
        }
        declineOutcome?.let { declined ->
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

    /**
     * Marks a 401 as the refusal of the token this route was sent with. Every route here pins its credential, so
     * the transport hands that 401 back unrefreshed.
     *
     * @param failure the transport's failure for the response, or null when it has none.
     * @param status the response's status.
     * @return a [PinnedTokenRefusal] for a 401, otherwise [failure] unchanged.
     */
    private fun pinnedRefusalOf(
        failure: PayabliException?,
        status: Int,
    ): PayabliException? =
        if (failure != null && status == HTTP_UNAUTHORIZED && failure.type == PayabliErrorType.TOKEN_EXPIRED) {
            PinnedTokenRefusal(failure.reason)
        } else {
            failure
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
