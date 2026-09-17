package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.taptopay.attestation.AttestationProjectStore
import com.payabli.sdk.taptopay.attestation.MintProjectResolver
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.impl.ClassicAttestor
import com.payabli.sdk.taptopay.attestation.impl.FAKE_CLOUD_PROJECT
import com.payabli.sdk.taptopay.attestation.impl.FakeClassicGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * End-to-end: enrollment pins the challenge project through register and mint, so an overwrite of the
 * shared store during register cannot change what Play Integrity is asked for.
 */
class DeviceEnrollmentProjectPinTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `enrollment mints the challenge project when the store is overwritten during register`() =
        runTest(timeout = TEST_TIMEOUT) {
            val enteredRegister = CompletableDeferred<Unit>()
            val releaseRegister = CompletableDeferred<Unit>()
            val script =
                RouteScript(
                    RouteScript.CHALLENGE to listOf(challengeBody(FAKE_CLOUD_PROJECT.toString())),
                    RouteScript.REGISTER to listOf(registerBody()),
                    RouteScript.ATTEST to listOf(attestBody()),
                )
            val storage = FakeSecureStore()
            val projects = AttestationProjectStore(storage)
            val mintProject = MintProjectResolver { projects.require(PayabliEnvironment.SANDBOX) }
            val gateway = FakeClassicGateway()
            val attestor = ClassicAttestor(gateway, cloudProjectNumber = { mintProject.resolve() })
            val transport =
                PausingRegisterTransport(script, enteredRegister, releaseRegister)
            val deviceKey = FakeDeviceKey()
            val enrollment =
                DeviceEnrollment(
                    entry = ENTRY,
                    appId = APP_ID,
                    client = DeviceServiceClient(transport),
                    attestor = attestor,
                    deviceKey = deviceKey,
                    signer = DeviceAssertionSigner(deviceKey, EnrollmentFixture.FIXED_CLOCK),
                    store = AttestedDeviceStore(storage),
                    projects = projects,
                    mintProject = mintProject,
                    environment = PayabliEnvironment.SANDBOX,
                    description = DeviceDescription(HARDWARE_ID, null, MODEL, OS_VERSION),
                    dispatcher = UnconfinedTestDispatcher(),
                )

            val enrolling = async { enrollment.enroll() }
            enteredRegister.await()
            // Concurrent enrollment would overwrite here; the pin must keep the challenge's number.
            AttestationProjectStore(storage).remember(PayabliEnvironment.SANDBOX, 222L)
            releaseRegister.complete(Unit)
            enrolling.await()

            assertEquals(listOf(FAKE_CLOUD_PROJECT), gateway.cloudProjectNumbers)
            assertEquals(222L, projects.require(PayabliEnvironment.SANDBOX))
        }
}

/**
 * Parks on the register call so a test can mutate the project store while enrollment is between
 * challenge and mint.
 */
private class PausingRegisterTransport(
    private val script: RouteScript,
    private val entered: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
) : PayabliTransport {
    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        if (request.path == RouteScript.REGISTER) {
            entered.complete(Unit)
            release.await()
        }
        yield()
        return script.respond(request)
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> =
        throw UnsupportedOperationException(
            "the device routes use the legacy envelope; a v2 decode here means the client chose the wrong seam",
        )
}
