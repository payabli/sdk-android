package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.FakeDeviceTransport
import com.payabli.sdk.taptopay.attestation.device.declineEnvelope
import com.payabli.sdk.taptopay.attestation.device.successEnvelope
import com.payabli.sdk.taptopay.attestation.impl.RecordingSdkLogger
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Distinct enough that a transposed pair fails an assertion instead of passing quietly. */
internal const val ENTRY = "entry-point-value"
internal const val APP_ID = "com.payabli.example"
internal const val DEVICE_ID = "device-id-value"
internal const val CHALLENGE_ID = "challenge-id-value"
internal const val HARDWARE_ID = "hardware-id-value"
internal const val MODEL = "model-value"
internal const val OS_VERSION = "os-version-value"
internal const val ACTIVATION_CODE = "123456"
internal const val RECORD_ENTRY = "com.payabli.sdk.taptopay.device.v1"

/** A base64 challenge, so the nonce derivation has something valid to decode. */
internal const val SERVER_CHALLENGE = "c2VydmVyLWlzc3VlZC1jaGFsbGVuZ2UtdmFsdWU="

internal fun challengeBody(): String =
    successEnvelope("""{"challengeId":"$CHALLENGE_ID","challenge":"$SERVER_CHALLENGE"}""")

internal fun registerBody(
    status: String = "pending",
    outcome: String? = null,
    deviceId: String = DEVICE_ID,
): String {
    val outcomeField = outcome?.let { ""","outcome":"$it"""" }.orEmpty()
    return successEnvelope("""{"deviceId":"$deviceId","status":"$status"$outcomeField}""")
}

internal fun attestBody(): String = successEnvelope("""{"registered":true,"isSandbox":false}""")

internal fun activateBody(): String = successEnvelope("""{"deviceId":"$DEVICE_ID","status":"active"}""")

/**
 * Answers each route from a script, and fails loudly on anything unscripted.
 *
 * `error` on a miss, the discipline the client's own tests state: an
 * unscripted route, or one call more than the script answers, has to fail by name here instead of replaying
 * the previous answer somewhere no assertion can see it.
 */
internal class RouteScript(
    private vararg val answers: Pair<String, List<String>>,
) {
    private val taken = mutableMapOf<String, Int>()

    fun respond(request: PayabliRequest): PayabliResponse {
        val route = request.path
        val queued =
            answers.firstOrNull { it.first == route }?.second
                ?: error("no answer scripted for $route")
        val index = taken.getOrDefault(route, 0)
        if (index >= queued.size) error("$route was called ${index + 1} times, ${queued.size} answers scripted")
        taken[route] = index + 1
        return PayabliResponse(200, body = queued[index].toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val CHALLENGE = "/api/v2/device/taptopay/challenge"
        const val REGISTER = "/api/v2/device/taptopay/register"
        const val ATTEST = "/api/v2/device/taptopay/attest"
        const val ACTIVATE = "/api/v2/device/taptopay/activate"
    }
}

/**
 * Everything a [DeviceEnrollment] needs, wired so a test can reach each part.
 *
 * The trace is shared by the transport and the store, because the property most worth asserting — that
 * nothing is written before the attestation succeeds — spans both of them and a per-fake list cannot show it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class EnrollmentFixture(
    script: RouteScript,
    val deviceKey: FakeDeviceKey = FakeDeviceKey(),
    val attestor: FakeAppAttestor = FakeAppAttestor(),
    storeFailure: (operation: String, key: String) -> SecureStorageException? = { _, _ -> null },
    hardwareId: String = HARDWARE_ID,
) {
    val trace: MutableList<String> = mutableListOf()
    val logger = RecordingSdkLogger()

    val transport =
        FakeDeviceTransport { request ->
            trace += request.path
            script.respond(request)
        }

    val storage = FakeSecureStore(failWith = storeFailure, trace = trace)
    val store = AttestedDeviceStore(storage, logger)

    val enrollment =
        DeviceEnrollment(
            entry = ENTRY,
            appId = APP_ID,
            client = DeviceServiceClient(transport, logger),
            attestor = attestor,
            deviceKey = deviceKey,
            signer = DeviceAssertionSigner(deviceKey, FIXED_CLOCK),
            store = store,
            description =
                DeviceDescription(
                    hardwareId = hardwareId,
                    deviceName = null,
                    model = MODEL,
                    osVersion = OS_VERSION,
                ),
            dispatcher = UnconfinedTestDispatcher(),
            logger = logger,
        )

    /** Writes the record an earlier successful run would have left. */
    fun seedRecord(
        entry: String = ENTRY,
        deviceId: String = DEVICE_ID,
        keyId: String = FakeDeviceKey.KEY_IDENTITY,
        activated: Boolean = true,
    ) {
        storage.seed(
            RECORD_ENTRY,
            PayabliJson.format
                .encodeToString(
                    AttestedDevice.serializer(),
                    AttestedDevice(entry, deviceId, keyId, activated),
                ).encodeToByteArray(),
        )
    }

    /** The stored record, decoded, or null when nothing is stored. */
    fun storedRecord(): AttestedDevice? =
        storage.peek(RECORD_ENTRY)?.let {
            PayabliJson.format.decodeFromString(AttestedDevice.serializer(), it.decodeToString())
        }

    /** Only the transport's half of the trace, for asserting call order alone. */
    val routes: List<String> get() = trace.filter { it.startsWith("/api/") }

    companion object {
        /** Fixed so an assertion signature is reproducible and the timestamp is not a moving value. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneOffset.UTC)
    }
}

/** A decline for [route], so a script can refuse one call and let the rest through. */
internal fun decline(
    resultCode: Int,
    reason: String,
): String = declineEnvelope(resultCode, reason)
