package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private val TEST_TIMEOUT = 5.seconds

/**
 * The gate that makes a throttle a refusal rather than advice.
 *
 * A virtual clock, so the window is exercised without the suite waiting out a real minute. `TestTimeSource`
 * is monotonic like the production source, which is the property the gate depends on.
 */
class ThrottleGateTest {
    private val clock = TestTimeSource()

    private fun gate(window: kotlin.time.Duration = 60.seconds) = ThrottleGate(window, clock)

    @Test
    fun `an untriggered gate lets everything through`() =
        runTest(timeout = TEST_TIMEOUT) {
            assertNull(runCatching { gate().check() }.exceptionOrNull())
        }

    @Test
    fun `once triggered the gate refuses without reaching the platform`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = gate()
            gate.record()

            val refusal = runCatching { gate.check() }.exceptionOrNull()

            assertTrue(refusal is AttestationException.Throttled)
            // The platform's own throttle code, because that is what the condition still is.
            assertEquals(IntegrityErrorCode.TOO_MANY_REQUESTS, (refusal as AttestationException).errorCode)
        }

    @Test
    fun `the gate reopens once the window has passed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = gate(window = 60.seconds)
            gate.record()

            clock += 59.seconds
            assertTrue(runCatching { gate.check() }.exceptionOrNull() is AttestationException.Throttled)

            clock += 2.seconds
            assertNull("the window must not outlive its duration", runCatching { gate.check() }.exceptionOrNull())
        }

    @Test
    fun `a second throttle extends the window from when it happened`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = gate(window = 60.seconds)
            gate.record()

            clock += 50.seconds
            gate.record()

            // 20s after the second throttle, so past the first window and inside the second.
            clock += 20.seconds
            assertTrue(runCatching { gate.check() }.exceptionOrNull() is AttestationException.Throttled)
        }

    // --- the attestors honour it, which is the part that matters ------------------------------------

    @Test
    fun `a throttled standard attestor stops calling the platform`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeStandardGateway(
                    onRequest = { _, _ -> throw IntegrityFailure(IntegrityErrorCode.TOO_MANY_REQUESTS) },
                )
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT, throttleGate = gate())

            val first =
                runCatching {
                    attestor.attest(AttestationChallenge.standard("Zmlyc3QtY2hhbGxlbmdl"))
                }.exceptionOrNull()
            val second =
                runCatching {
                    attestor.attest(AttestationChallenge.standard("c2Vjb25kLWNoYWxsZW5nZQ"))
                }.exceptionOrNull()

            assertTrue(first is AttestationException.Throttled)
            assertTrue(second is AttestationException.Throttled)
            // One request, not two. Without the gate the second attestation asks a budget already known
            // to be spent, which is the loop the gate exists to break.
            assertEquals(1, gateway.requestHashes.size)
        }

    @Test
    fun `a refused attempt does not spend the challenge`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The gate runs before the ledger, so a caller refused by the gate can present the same
            // challenge once the window closes rather than having to obtain another.
            // Counted per request, not per preparation: the provider prepared by the first attestation is
            // reused by the third, so keying the failure on the preparation index would fail it twice.
            var requests = 0
            val gateway =
                FakeStandardGateway(
                    onRequest = { _, _ ->
                        requests++
                        if (requests == 1) throw IntegrityFailure(IntegrityErrorCode.TOO_MANY_REQUESTS)
                        FAKE_TOKEN
                    },
                )
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT, throttleGate = gate())
            val unspent = AttestationChallenge.standard("dW5zcGVudC1jaGFsbGVuZ2U")

            runCatching { attestor.attest(AttestationChallenge.standard("Zmlyc3QtY2hhbGxlbmdl")) }
            val refused = runCatching { attestor.attest(unspent) }.exceptionOrNull()
            assertTrue(refused is AttestationException.Throttled)

            clock += 61.seconds

            // Accepted, not ChallengeReused: the refused attempt never consumed it.
            assertEquals(FAKE_TOKEN, attestor.attest(unspent).value)
        }

    @Test
    fun `a throttled classic attestor stops calling the platform`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeClassicGateway(
                    onRequest = { _, _ -> throw IntegrityFailure(IntegrityErrorCode.TOO_MANY_REQUESTS) },
                )
            val attestor = ClassicAttestor(gateway, throttleGate = gate())

            runCatching { attestor.attest(AttestationChallenge.classic("Zmlyc3QtY2xhc3NpYy1ub25jZQ")) }
            val second =
                runCatching {
                    attestor.attest(AttestationChallenge.classic("c2Vjb25kLWNsYXNzaWMtbm9uY2U"))
                }.exceptionOrNull()

            assertTrue(second is AttestationException.Throttled)
            assertEquals(1, gateway.nonces.size)
        }

    @Test
    fun `warmUp honours the gate rather than walking past it`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Preparing is itself a platform request and can be refused for a spent budget. Nothing is
            // cached when it fails, so a warm-up loop without this check reaches the platform every time.
            val gateway =
                FakeStandardGateway(
                    onPrepare = { throw IntegrityFailure(IntegrityErrorCode.TOO_MANY_REQUESTS) },
                )
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT, throttleGate = gate())

            assertTrue(runCatching { attestor.warmUp() }.exceptionOrNull() is AttestationException.Throttled)
            assertTrue(runCatching { attestor.warmUp() }.exceptionOrNull() is AttestationException.Throttled)

            assertEquals("the second warm-up must not reach the platform", 1, gateway.prepares.get())
        }

    @Test
    fun `any other failure leaves the gate open`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Only a spent budget closes it. A network blip is this device's problem and must not stop the
            // next attestation from trying.
            val gateway =
                FakeClassicGateway(
                    onRequest = { _, _ -> throw IntegrityFailure(IntegrityErrorCode.NETWORK_ERROR) },
                )
            val attestor = ClassicAttestor(gateway, throttleGate = gate())

            runCatching { attestor.attest(AttestationChallenge.classic("Zmlyc3QtY2xhc3NpYy1ub25jZQ")) }
            runCatching { attestor.attest(AttestationChallenge.classic("c2Vjb25kLWNsYXNzaWMtbm9uY2U")) }

            assertEquals(2, gateway.nonces.size)
        }
}
