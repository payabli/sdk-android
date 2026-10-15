package com.payabli.sdk.taptopay.enrollment.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.AttestationProjectStore
import com.payabli.sdk.taptopay.attestation.MintProjectResolver
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceDescription
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.EnrollmentOutcome
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay.LIVE_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 180.seconds
private const val TIMEOUT_MILLIS = 15_000

/**
 * Whether a device attested and activated under one token is still served `/config` under another.
 *
 * Run as [DeviceActivationLiveTest] is, by class name. Each run registers one new device on the paypoint
 * and spends one activation code.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class TokenRotationLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A record a previous run left would make the enrollment below warm, so the run starts cold. */
    @Before
    fun startCold() =
        runTest(timeout = TEST_TIMEOUT) {
            LiveTapToPay.requireWiredHandset("the live tier is for wired handsets")
            AttestedDeviceStore(DeviceTrust.open(context).store).clear(LiveRunSettings.entry)
        }

    @After
    fun forgetTheDevice() =
        runTest(timeout = TEST_TIMEOUT) {
            AttestedDeviceStore(DeviceTrust.open(context).store).clear(LiveRunSettings.entry)
        }

    @Test
    fun aDeviceActivatedUnderOneTokenIsServedConfigUnderAnother() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val first = RecordingProvider()
                val firstClient = DeviceServiceClient(session(first).transport)
                val enrollment = enrollment(firstClient, freshDescription())

                val outcome = enrollment.enroll()
                assertTrue(
                    "a device registered for this run must be pending, got $outcome",
                    (outcome as EnrollmentOutcome.Attested).activationRequired,
                )
                val record = AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)!!
                enrollment.activateDevice(LiveTapToPay.mintActivationCode(record.deviceId))

                val sameToken = configAnswer(firstClient, enrollment)
                Log.i(LIVE_TAG, "config under the attesting token: $sameToken, tokens ${first.fingerprints}")

                // The SDK holds one session per process, so the fresh token is sent by hand. The token server's
                // access-token route answers a cached token; its exchange route always mints.
                val fresh = mintedToken()
                val freshPrint = fingerprint(fresh)
                val otherToken = rawConfig(fresh, enrollment.assertion()!!.asHeaders())
                Log.i(LIVE_TAG, "config under a fresh token $freshPrint: $otherToken")

                assertTrue(
                    "no token reached this run's provider: another live class installed the session first",
                    first.fingerprints.isNotEmpty(),
                )
                assertNotEquals("the fresh token is the attesting one", first.fingerprints.first(), freshPrint)
                assertTrue("under the attesting token: $sameToken", sameToken == "200")
                assertTrue("under a fresh token: $otherToken", otherToken == "200")
            }
        }

    /** A token the token server mints for this call, never its cached one. */
    private fun mintedToken(): String {
        val endpoint = LiveRunSettings.tokenEndpoint.replace("/payabli/access-token", "/payabli/exchange-token")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            check(
                connection.responseCode == HttpURLConnection.HTTP_OK,
            ) { "exchange-token answered ${connection.responseCode}" }
            Json
                .parseToJsonElement(connection.inputStream.bufferedReader().readText())
                .jsonObject["accessToken"]!!
                .jsonPrimitive
                .content
        } finally {
            connection.disconnect()
        }
    }

    /** `200`, or the status and the refusal's body. A 200 body carries reader credentials, so it is never kept. */
    private fun rawConfig(
        token: String,
        assertion: Map<String, String>,
    ): String {
        val url = URL("${LiveRunSettings.baseUrl}/api/v2/device/taptopay/config/${LiveRunSettings.entry}")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", "Bearer $token")
            assertion.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) {
                "200"
            } else {
                "$status ${connection.errorStream?.bufferedReader()?.readText().orEmpty()}"
            }
        } finally {
            connection.disconnect()
        }
    }

    /** `200`, or the refusal's class, result code and wording. */
    private suspend fun configAnswer(
        client: DeviceServiceClient,
        enrollment: DeviceEnrollment,
    ): String =
        try {
            client.config(LiveRunSettings.entry, enrollment.assertion()!!)
            "200"
        } catch (refused: DeviceServiceException) {
            "${refused.javaClass.simpleName}(${refused.resultCode}): ${refused.reason}"
        }

    /** Hands out the token server's token on each call and keeps the first 8 hex of each one's SHA-256. */
    private inner class RecordingProvider {
        val fingerprints = mutableListOf<String>()

        fun next(): String = LiveRunSettings.accessToken().also { token -> fingerprints += fingerprint(token) }
    }

    /** The first 8 hex of the token's SHA-256, which is all a log may carry of it. */
    private fun fingerprint(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }

    private suspend fun session(provider: RecordingProvider): PayabliSession =
        PayabliSession.initialize(
            PayabliConfig(
                entryPoint = LiveRunSettings.entry,
                environment = LiveRunSettings.environment,
                tokenProvider = { provider.next() },
            ),
            HostBindings(context),
        )

    private suspend fun enrollment(
        client: DeviceServiceClient,
        description: DeviceDescription,
    ): DeviceEnrollment {
        val trust = DeviceTrust.open(context)
        val projects = AttestationProjectStore(trust.store)
        val environment = LiveRunSettings.environment
        val mintProject = MintProjectResolver { projects.require(environment) }
        projects.remember(environment, LiveTapToPay.cloudProjectNumber())
        return DeviceEnrollment(
            entry = LiveRunSettings.entry,
            appId = context.packageName,
            client = client,
            attestor = AttestorFactory.classic(context) { mintProject.resolve() },
            deviceKey = trust.key,
            signer = DeviceAssertionSigner(trust.key),
            store = AttestedDeviceStore(trust.store),
            projects = projects,
            mintProject = mintProject,
            environment = environment,
            description = description,
            dispatcher = Dispatchers.IO,
        )
    }

    /** A device identity nothing has used before, so the service row is new and pending. */
    private fun freshDescription(): DeviceDescription {
        val base = DeviceDescriptionFactory.create(context)
        val hex =
            MessageDigest
                .getInstance("SHA-256")
                .digest("${base.hardwareId}|${UUID.randomUUID()}".toByteArray(Charsets.UTF_8))
                .take(16)
                .joinToString("") { "%02x".format(it) }
        return DeviceDescription(hex, base.deviceName, base.model, base.osVersion)
    }
}
