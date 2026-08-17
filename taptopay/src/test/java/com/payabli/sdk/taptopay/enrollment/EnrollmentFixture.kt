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
internal const val RECORD_ENTRY = "com.payabli.sdk.taptopay.device.v2"

/** The single-binding entry the store carries forward and removes. */
internal const val LEGACY_RECORD_ENTRY = "com.payabli.sdk.taptopay.device.v1"

/** A second entry point, for the case where one device serves more than one. */
internal const val OTHER_ENTRY = "other-entry-point-value"

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

/** Every credential the reader is given, each value naming its own field so a transposed pair fails. */
internal fun configBody(): String =
    successEnvelope(
        """
        {"credentials":{"platform":"android","secretKey":"secret-key-value","apiKey":"api-key-value",
        "merchantId":"merchant-id-value","environment":"sandbox","currencyCode":"USD",
        "merchantName":"merchant-name-value","merchantCategoryCode":"5999","terminalId":"terminal-id-value",
        "ppId":"pp-id-value","hostPort":"host-port-value"}}
        """.trimIndent().replace("\n", ""),
    )

/**
 * Answers each request from a script, keyed on the path it was sent to, and fails loudly on anything
 * unscripted.
 *
 * **Paths, not route templates.** A script answers what the client sent, and `/config` resolves its
 * `{entry}` before sending. The templates are the transport's recordable form and are asserted separately,
 * in `DeviceServiceConfigTest`, which pins that a resolved path names a paypoint and a template does not.
 *
 * `error` on a miss, the discipline the client's own tests state: an unscripted path, or one call more than
 * the script answers, has to fail by name here instead of replaying the previous answer somewhere no
 * assertion can see it.
 */
internal class RouteScript(
    private vararg val answers: Pair<String, List<String>>,
    /** These routes answer a refusal inside a 200, so a real status is only for a route that skips them. */
    private val statusFor: (String) -> Int = { 200 },
) {
    private val taken = mutableMapOf<String, Int>()

    fun respond(request: PayabliRequest): PayabliResponse {
        val path = request.path
        val queued =
            answers.firstOrNull { it.first == path }?.second
                ?: error("no answer scripted for $path")
        val index = taken.getOrDefault(path, 0)
        if (index >= queued.size) error("$path was called ${index + 1} times, ${queued.size} answers scripted")
        taken[path] = index + 1
        return PayabliResponse(statusFor(path), body = queued[index].toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val CHALLENGE = "/api/v2/device/taptopay/challenge"
        const val REGISTER = "/api/v2/device/taptopay/register"
        const val ATTEST = "/api/v2/device/taptopay/attest"
        const val ACTIVATE = "/api/v2/device/taptopay/activate"

        /** Resolved, since that is what the client sends. The four above resolve to themselves. */
        const val CONFIG = "/api/v2/device/taptopay/config/$ENTRY"
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
    firstReadGate: (suspend () -> Unit)? = null,
) {
    val trace: MutableList<String> = mutableListOf()
    val logger = RecordingSdkLogger()

    val transport =
        FakeDeviceTransport { request ->
            trace += request.path
            script.respond(request)
        }

    val storage = FakeSecureStore(failWith = storeFailure, trace = trace, firstReadGate = firstReadGate)
    val store = AttestedDeviceStore(storage, logger)

    val client = DeviceServiceClient(transport, logger)

    val enrollment =
        DeviceEnrollment(
            entry = ENTRY,
            appId = APP_ID,
            client = client,
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

    /**
     * A second coordinator for [entry], over the same store, key and transport.
     *
     * What one device serving two entry points actually looks like: separate coordinators, each holding its
     * own entry, sharing the one store every binding lives in.
     */
    fun enrollmentFor(entry: String): DeviceEnrollment =
        DeviceEnrollment(
            entry = entry,
            appId = APP_ID,
            client = client,
            attestor = attestor,
            deviceKey = deviceKey,
            signer = DeviceAssertionSigner(deviceKey, FIXED_CLOCK),
            store = store,
            description =
                DeviceDescription(
                    hardwareId = HARDWARE_ID,
                    deviceName = null,
                    model = MODEL,
                    osVersion = OS_VERSION,
                ),
            dispatcher = UnconfinedTestDispatcher(),
            logger = logger,
        )

    /** Writes the bindings an earlier successful run would have left, most recently used first. */
    fun seedBindings(vararg held: AttestedDevice) {
        storage.seed(
            RECORD_ENTRY,
            PayabliJson.format
                .encodeToString(DeviceBindings.serializer(), DeviceBindings(held.toList()))
                .encodeToByteArray(),
        )
    }

    /** Writes one binding, the way a run against a single entry point would have left it. */
    fun seedRecord(
        entry: String = ENTRY,
        deviceId: String = DEVICE_ID,
        keyId: String = FakeDeviceKey.KEY_IDENTITY,
    ) = seedBindings(AttestedDevice(entry, deviceId, keyId))

    /** Writes the single-binding shape an install from before the collection existed would carry. */
    fun seedLegacyRecord(
        entry: String = ENTRY,
        deviceId: String = DEVICE_ID,
        keyId: String = FakeDeviceKey.KEY_IDENTITY,
    ) {
        storage.seed(
            LEGACY_RECORD_ENTRY,
            PayabliJson.format
                .encodeToString(AttestedDevice.serializer(), AttestedDevice(entry, deviceId, keyId))
                .encodeToByteArray(),
        )
    }

    /** Everything stored, decoded, or null when nothing is stored. */
    fun storedBindings(): DeviceBindings? =
        storage.peek(RECORD_ENTRY)?.let {
            PayabliJson.format.decodeFromString(DeviceBindings.serializer(), it.decodeToString())
        }

    /** The stored binding for [entry], or null when none is held for it. */
    fun storedRecord(entry: String = ENTRY): AttestedDevice? = storedBindings()?.forEntry(entry)

    /**
     * Only the transport's half of the trace, for asserting call order alone.
     *
     * Paths, as sent. `/config` appears with its `{entry}` resolved.
     */
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
