package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/** Threads contending for one challenge, and how many times the contention is repeated. */
private const val RACERS = 8
private const val ROUNDS = 5

/** Long enough that every racer is inside the check-and-insert window together. */
private const val SLOW_ADD_MILLIS = 20L

/**
 * A set whose membership check and insert are slow enough that concurrent callers overlap inside the
 * window the ledger's mutex exists to close. Insertion-ordered, as the ledger requires.
 */
private class SlowSet(
    private val delegate: LinkedHashSet<String> = LinkedHashSet(),
) : MutableSet<String> by delegate {
    override fun add(element: String): Boolean {
        // Reports what an unsynchronised check-then-act reports: every caller that observed absence claims
        // to have inserted. Returning the delegate's own result instead hides the lost update behind the
        // delegate's internal ordering, and the test then passes four times in five against a ledger with
        // no mutex.
        val absent = !delegate.contains(element)
        Thread.sleep(SLOW_ADD_MILLIS)
        delegate.add(element)
        return absent
    }
}

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
            // so without this it leaves no trace at all, which is the wrong way round: it is the only
            // failure that is unambiguously a bug in the calling code rather than a device condition.
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
            assertEquals(listOf("event", "verdictClass"), record.fieldNames)
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
    fun `two sequential callers with the same challenge produce one attestation and one refusal`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Sequential, and named so. `runTest` drives a single-threaded scheduler and the fake never
            // suspends, so the first coroutine finishes before the second starts however this is written.
            // The concurrent guarantee is the next test; this one covers the ordinary double-spend.
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
    fun `genuinely simultaneous callers still produce exactly one attestation`() {
        // Real threads, because the guarantee is about synchronisation and a single-threaded scheduler
        // cannot exercise it. With `ChallengeLedger`'s mutex removed the whole suite still passes, so the
        // sequential test above defends nothing about concurrent access.
        //
        // Dispatchers.IO rather than Default: the barrier blocks its thread, and IO is sized for that.
        // Repeated, because a lost update is a race rather than a certainty; the repeats are what make an
        // unsynchronised ledger fail reliably instead of occasionally.
        repeat(ROUNDS) { round ->
            val gateway = FakeStandardGateway()
            // A backing set that dawdles inside the check-and-insert. Held under the mutex the delay is
            // serialised and nothing changes; without it every racer is inside the window at once, so a
            // lost update stops being a matter of timing luck.
            val attestor =
                StandardAttestor(gateway, FAKE_CLOUD_PROJECT, ledger = ChallengeLedger(SlowSet()))
            val challenge = AttestationChallenge.standard("c2ltdWx0YW5lb3VzLSRyb3VuZA$round")
            val barrier = CyclicBarrier(RACERS)

            val outcomes =
                runBlocking {
                    (1..RACERS)
                        .map {
                            async(Dispatchers.IO) {
                                // Fails the test rather than hanging it if a racer never arrives.
                                barrier.await(5, TimeUnit.SECONDS)
                                runCatching { attestor.attest(challenge) }.exceptionOrNull()
                            }
                        }.awaitAll()
                }

            assertEquals("round $round reached the platform more than once", 1, gateway.requestHashes.size)
            assertEquals("round $round", 1, outcomes.count { it == null })
            assertEquals(
                "round $round",
                RACERS - 1,
                outcomes.count { it is AttestationException.ChallengeReused },
            )
        }
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
            // The ledger is bounded: an attestor lives as long as the app, and remembering every value ever
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
