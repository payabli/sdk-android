package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private fun challenge(hash: String = "c2hhLTI1Ni1kaWdlc3Q") = AttestationChallenge.standard(hash)

/** The failure from a suspending call, or null if it succeeded. Keeps every assertion inside `runTest`. */
private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

class StandardAttestorTest {
    private val logger = RecordingSdkLogger()

    private fun attestorFor(gateway: FakeStandardGateway) =
        StandardAttestor(gateway, FAKE_CLOUD_PROJECT, logger = logger)

    // --- the happy path -----------------------------------------------------------------------------

    @Test
    fun `the token comes back exactly as the platform produced it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway(onRequest = { _, _ -> "  a.token.with padding and spaces  " })

            val token = attestorFor(gateway).attest(challenge())

            // Byte for byte. Nothing trims it, parses it, or checks that it looks like a JWT.
            assertEquals("  a.token.with padding and spaces  ", token.value)
        }

    @Test
    fun `the challenge reaches the platform verbatim as the request hash`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()

            attestorFor(gateway).attest(challenge("Zm9vLWJhci1iYXo"))

            assertEquals(listOf("Zm9vLWJhci1iYXo"), gateway.requestHashes)
        }

    @Test
    fun `the cloud project number reaches the platform`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()

            attestorFor(gateway).attest(challenge())

            assertEquals(listOf(FAKE_CLOUD_PROJECT), gateway.cloudProjectNumbers)
        }

    @Test
    fun `a classic challenge is refused rather than sent as a request hash`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()

            val failure = failureOf { attestorFor(gateway).attest(AttestationChallenge.classic("aaaaaaaaaaaaaaaa")) }

            assertTrue(failure is IllegalArgumentException)
            assertEquals(0, gateway.prepares.get())
        }

    // --- preparation --------------------------------------------------------------------------------

    @Test
    fun `the provider is prepared once and reused`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()
            val attestor = attestorFor(gateway)

            attestor.attest(challenge("aGFzaC1vbmU"))
            attestor.attest(challenge("aGFzaC10d28"))
            attestor.attest(challenge("aGFzaC10aHJlZQ"))

            assertEquals(1, gateway.prepares.get())
            assertEquals(3, gateway.requestHashes.size)
        }

    @Test
    fun `warmUp prepares, and a later attestation reuses what it prepared`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeStandardGateway()
            val attestor = attestorFor(gateway)

            attestor.warmUp()
            assertEquals(1, gateway.prepares.get())
            assertEquals(0, gateway.requestHashes.size)

            attestor.attest(challenge())
            assertEquals(1, gateway.prepares.get())
        }

    @Test
    fun `concurrent attestations share a single preparation`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Preparing is a network round trip. Ten concurrent attestations must cost one, not ten, and
            // the gate makes the race deterministic: the preparation cannot finish until all ten have
            // started, so every one of them is genuinely in flight at the same time.
            val released = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            var pending = 10
            val gateway = FakeStandardGateway(onPrepare = { released.await() })
            val attestor = attestorFor(gateway)

            val attestations =
                (1..10).map { index ->
                    async {
                        if (--pending == 0) started.complete(Unit)
                        attestor.attest(challenge("aGFzaC1udW1iZXI$index"))
                    }
                }
            started.await()
            released.complete(Unit)
            attestations.awaitAll()

            assertEquals(1, gateway.prepares.get())
            assertEquals(10, gateway.requestHashes.size)
        }

    @Test
    fun `a failed preparation is mapped, and the next attestation prepares again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeStandardGateway(
                    onPrepare = { attempt ->
                        if (attempt == 1) throw IntegrityFailure(StandardIntegrityErrorCode.NETWORK_ERROR)
                    },
                )
            val attestor = attestorFor(gateway)

            val failure = failureOf { attestor.attest(challenge("Zmlyc3QtY2hhbGxlbmdl")) }

            assertTrue(failure is AttestationException.Retryable)
            assertEquals(StandardIntegrityErrorCode.NETWORK_ERROR, (failure as AttestationException).errorCode)

            // A failed preparation must not be cached as though it had succeeded, or the attestor is dead
            // for the rest of its life over one dropped connection.
            attestor.attest(challenge("c2Vjb25kLWNoYWxsZW5nZQ"))
            assertEquals(2, gateway.prepares.get())
        }

    // --- the expired provider -----------------------------------------------------------------------

    @Test
    fun `an invalid provider is replaced once and the request retried`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeStandardGateway(
                    onRequest = { preparation, _ ->
                        if (preparation == 1) {
                            throw IntegrityFailure(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID)
                        }
                        FAKE_TOKEN
                    },
                )

            val token = attestorFor(gateway).attest(challenge())

            assertEquals(FAKE_TOKEN, token.value)
            assertEquals(2, gateway.prepares.get())
            assertEquals(2, gateway.requestHashes.size)
        }

    @Test
    fun `a second invalid provider is surfaced rather than retried again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeStandardGateway(
                    onRequest = { _, _ ->
                        throw IntegrityFailure(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID)
                    },
                )

            val failure = failureOf { attestorFor(gateway).attest(challenge()) }

            assertTrue(failure is AttestationException.Retryable)
            assertEquals(
                StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID,
                (failure as AttestationException).errorCode,
            )
            // Exactly one retry. Looping would turn a permanent condition into latency and hide it.
            assertEquals(2, gateway.prepares.get())
            assertEquals(2, gateway.requestHashes.size)
        }

    @Test
    fun `any other error is surfaced without discarding the provider`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeStandardGateway(
                    onRequest = { _, _ -> throw IntegrityFailure(StandardIntegrityErrorCode.TOO_MANY_REQUESTS) },
                )
            val attestor = attestorFor(gateway)

            assertTrue(failureOf { attestor.attest(challenge("Zmlyc3QtY2hhbGxlbmdl")) } is AttestationException)
            assertTrue(failureOf { attestor.attest(challenge("c2Vjb25kLWNoYWxsZW5nZQ")) } is AttestationException)

            // Throttling says nothing about the provider, so throwing it away would buy an extra network
            // round trip per failure at exactly the moment the platform is asking for fewer of them.
            assertEquals(1, gateway.prepares.get())
        }

    @Test
    fun `a stale invalid-provider report does not discard the replacement`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Two attestations hold the same provider when it expires, and both are told it is invalid.
            // The first replaces it; the second must notice that what it is about to throw away is no
            // longer what it was holding, or one expiry becomes a run of preparations under load.
            val secondReachedPlatform = CompletableDeferred<Unit>()
            val secondMayFail = CompletableDeferred<Unit>()
            val gateway =
                FakeStandardGateway(
                    onRequest = { preparation, hash ->
                        when {
                            preparation > 1 -> FAKE_TOKEN
                            hash == "c2Vjb25k" -> {
                                secondReachedPlatform.complete(Unit)
                                secondMayFail.await()
                                throw IntegrityFailure(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID)
                            }
                            else ->
                                throw IntegrityFailure(StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID)
                        }
                    },
                )
            val attestor = attestorFor(gateway)
            attestor.warmUp()

            // Takes provider 1 and parks inside the request, so it is holding it when the first replaces it.
            val second = async { attestor.attest(challenge("c2Vjb25k")) }
            secondReachedPlatform.await()
            // Runs to completion: fails on provider 1, prepares provider 2, succeeds on it.
            val first = attestor.attest(challenge("Zmlyc3Q"))
            secondMayFail.complete(Unit)

            assertEquals(FAKE_TOKEN, first.value)
            assertEquals(FAKE_TOKEN, second.await().value)
            // One warm-up plus one replacement. A third would mean the second attestation discarded a
            // provider it had never held.
            assertEquals(2, gateway.prepares.get())
        }

    // --- diagnostics --------------------------------------------------------------------------------

    @Test
    fun `a failure logs exactly three fields and never the challenge`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeStandardGateway(
                    onRequest = { _, _ -> throw IntegrityFailure(StandardIntegrityErrorCode.TOO_MANY_REQUESTS) },
                )

            failureOf { attestorFor(gateway).attest(challenge("c2VjcmV0LWNoYWxsZW5nZQ")) }

            val record = logger.records.single()
            assertEquals(LogLevel.ERROR, record.level)
            // An exact set, not a contains-check. The guarantee is that this path hands the logger these
            // three and nothing else, so a fourth field added later has to be justified rather than
            // sliding in unnoticed. Whether an allowlisted value renders correctly is :core's question
            // and :core tests it; the challenge is never passed as a field at all, so it cannot leak.
            assertEquals(setOf("event", "verdictClass", "errorCode"), record.fieldNames)
            assertFalse(record.message.contains("c2VjcmV0LWNoYWxsZW5nZQ"))
        }
}
