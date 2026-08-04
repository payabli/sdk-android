package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * A challenge is spent by being offered, and the enforcement is easy to fake convincingly.
 *
 * The naive implementation records a challenge on success and passes the obvious test. Two of the cases
 * below are the ones that catch it: a **failed** attestation must still consume the value, and a *reused
 * value* must be refused even when it arrives as a different object.
 */
class SingleUseChallengeTest {
    @Test
    fun `the same challenge cannot be attested twice`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT)
            val challenge = AttestationChallenge.standard("c2luZ2xlLXVzZS12YWx1ZQ")

            attestor.attest(challenge)
            val second = runCatching { attestor.attest(challenge) }.exceptionOrNull()

            assertTrue(second is AttestationException.ChallengeReused)
            assertEquals("only the first attestation reaches the platform", 1, gateway.requestHashes.size)
        }

    @Test
    fun `a second challenge object carrying the same value is refused too`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The value is what a verifier retires, not the object holding it.
            //
            // Both strings are built at run time rather than written as literals, and that is the whole
            // test. Two identical literals are the same interned instance, so an implementation comparing
            // references passes against them and fails only here. Sabotaging the ledger to compare identity
            // confirmed exactly that: with literals this test stayed green.
            val value = "c2FtZS12YWx1ZS10d2ljZQ"
            val first = String(value.toCharArray())
            val second = String(value.toCharArray())
            assertNotSame("the test proves nothing unless these are distinct instances", first, second)

            val gateway = FakeStandardGateway()
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT)

            attestor.attest(AttestationChallenge.standard(first))
            val outcome =
                runCatching { attestor.attest(AttestationChallenge.standard(second)) }.exceptionOrNull()

            assertTrue(outcome is AttestationException.ChallengeReused)
            assertEquals(1, gateway.requestHashes.size)
        }

    @Test
    fun `a challenge whose attestation failed is still spent`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The requirement most likely to be implemented backwards. Recording only successes reads as
            // generous, and it is exactly the replay the guard exists to refuse: the platform may well
            // have seen the value already.
            val gateway =
                FakeStandardGateway(
                    onRequest = { _, _ -> throw IntegrityFailure(StandardIntegrityErrorCode.NETWORK_ERROR) },
                )
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT)
            val challenge = AttestationChallenge.standard("c3BlbnQtb24tZmFpbHVyZQ")

            val first = runCatching { attestor.attest(challenge) }.exceptionOrNull()
            val second = runCatching { attestor.attest(challenge) }.exceptionOrNull()

            assertTrue(first is AttestationException.Retryable)
            assertTrue(
                "a retry must need a new challenge, not the spent one",
                second is AttestationException.ChallengeReused,
            )
            assertEquals(1, gateway.requestHashes.size)
        }

    @Test
    fun `a reused challenge is logged, since it is the one failure that is the caller's own`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Every other failure logs on its way out. This one is raised before the platform is consulted,
            // so it used to leave no trace at all, which is the wrong way round: it is the only failure that
            // is unambiguously a bug in the calling code rather than a device or platform condition.
            val logger = RecordingSdkLogger()
            val gateway = FakeStandardGateway()
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT, logger = logger)
            val challenge = AttestationChallenge.standard("bG9nZ2VkLW9uLXJldXNl")

            attestor.attest(challenge)
            assertTrue(logger.records.isEmpty())

            runCatching { attestor.attest(challenge) }

            val record = logger.records.single()
            assertEquals(LogLevel.ERROR, record.level)
            // No errorCode: there is no platform code, and inventing one would imply the platform spoke.
            assertEquals(setOf("event", "verdictClass"), record.fieldNames)
            assertFalse("the challenge itself never reaches the log", record.message.contains("bG9nZ2VkLW9uLXJldXNl"))
        }

    @Test
    fun `the guard holds for classic attestations too`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeClassicGateway()
            val attestor = ClassicAttestor(gateway)
            val challenge = AttestationChallenge.classic("Y2xhc3NpYy1zaW5nbGUtdXNl")

            attestor.attest(challenge)
            val second = runCatching { attestor.attest(challenge) }.exceptionOrNull()

            assertTrue(second is AttestationException.ChallengeReused)
            assertEquals(1, gateway.nonces.size)
        }

    @Test
    fun `two callers racing the same challenge produce one attestation and one refusal`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT)
            val challenge = AttestationChallenge.standard("cmFjZWQtY2hhbGxlbmdl")

            val outcomes =
                (1..2)
                    .map { async { runCatching { attestor.attest(challenge) }.exceptionOrNull() } }
                    .awaitAll()

            assertEquals(1, gateway.requestHashes.size)
            assertEquals(1, outcomes.count { it is AttestationException.ChallengeReused })
            assertEquals(1, outcomes.count { it == null })
        }

    @Test
    fun `different challenges are unaffected by each other`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()
            val attestor = StandardAttestor(gateway, FAKE_CLOUD_PROJECT)

            assertNull(runCatching { attestor.attest(AttestationChallenge.standard("Zmlyc3Q")) }.exceptionOrNull())
            assertNull(runCatching { attestor.attest(AttestationChallenge.standard("c2Vjb25k")) }.exceptionOrNull())

            assertEquals(2, gateway.requestHashes.size)
        }

    @Test
    fun `the ledger forgets the oldest once it is full`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Bounded on purpose: an attestor lives as long as the app, and remembering every value ever
            // seen is a leak with no ceiling. What this pins is that the bound exists and that the value
            // it forgets is the oldest one, so the guard stays useful for recent traffic.
            val ledger = ChallengeLedger()
            ledger.spend("the-oldest-value")
            repeat(256) { ledger.spend("filler-$it") }

            // Evicted, so it is accepted again.
            assertNull(runCatching { ledger.spend("the-oldest-value") }.exceptionOrNull())
            // Still remembered.
            assertTrue(
                runCatching { ledger.spend("filler-255") }.exceptionOrNull() is AttestationException.ChallengeReused,
            )
        }
}
