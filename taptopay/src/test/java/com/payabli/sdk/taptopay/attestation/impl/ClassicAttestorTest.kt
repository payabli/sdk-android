package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private fun challenge(nonce: String = "c2VydmVyLWlzc3VlZC1ub25jZQ") = AttestationChallenge.classic(nonce)

class ClassicAttestorTest {
    private val logger = RecordingSdkLogger()

    private fun attestorFor(
        gateway: FakeClassicGateway,
        cloudProjectNumber: Long? = null,
    ) = ClassicAttestor(gateway, cloudProjectNumber, logger = logger)

    @Test
    fun `the token comes back exactly as the platform produced it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeClassicGateway(onRequest = { _, _ -> "  a.token.with padding and spaces  " })

            val token = attestorFor(gateway).attest(challenge())

            assertEquals("  a.token.with padding and spaces  ", token.value)
        }

    @Test
    fun `the challenge reaches the platform verbatim as the nonce`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeClassicGateway()

            attestorFor(gateway).attest(challenge("YS1zZXJ2ZXItaXNzdWVkLW5vbmNl"))

            assertEquals(listOf("YS1zZXJ2ZXItaXNzdWVkLW5vbmNl"), gateway.nonces)
        }

    @Test
    fun `the cloud project number is sent only when there is one`() =
        runTest(timeout = TEST_TIMEOUT) {
            val withNumber = FakeClassicGateway()
            val withoutNumber = FakeClassicGateway()

            attestorFor(withNumber, FAKE_CLOUD_PROJECT).attest(challenge())
            attestorFor(withoutNumber, null).attest(challenge())

            assertEquals(listOf(FAKE_CLOUD_PROJECT), withNumber.cloudProjectNumbers)
            // Null rather than a placeholder: an app whose Play Console listing carries the linkage needs
            // no explicit number, and inventing one would fail a request that would otherwise have worked.
            assertEquals(listOf(null), withoutNumber.cloudProjectNumbers)
        }

    @Test
    fun `nothing is cached between attestations`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeClassicGateway()
            val attestor = attestorFor(gateway)

            attestor.attest(challenge("bm9uY2UtbnVtYmVyLW9uZQ"))
            attestor.attest(challenge("bm9uY2UtbnVtYmVyLXR3bw"))

            // A classic request reaches Google's servers every time by design, and the platform advises
            // against caching what comes back. Two attestations are two calls.
            assertEquals(2, gateway.nonces.size)
        }

    @Test
    fun `warmUp does nothing and reaches nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeClassicGateway()

            attestorFor(gateway).warmUp()

            assertTrue(gateway.nonces.isEmpty())
        }

    @Test
    fun `a standard challenge is refused rather than sent as a nonce`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeClassicGateway()

            val failure =
                runCatching {
                    attestorFor(gateway).attest(AttestationChallenge.standard("a-request-hash"))
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(gateway.nonces.isEmpty())
        }

    @Test
    fun `a platform error is mapped against the classic table`() =
        runTest(timeout = TEST_TIMEOUT) {
            // -17 is the collision: transient here, a misconfiguration for a standard request. Mapping a
            // classic failure against the wrong table would report this as an SDK bug and never retry it.
            val gateway =
                FakeClassicGateway(onRequest = {
                    _,
                    _,
                    ->
                    throw IntegrityFailure(IntegrityErrorCode.CLIENT_TRANSIENT_ERROR)
                })

            val failure = runCatching { attestorFor(gateway).attest(challenge()) }.exceptionOrNull()

            assertTrue(failure is AttestationException.Retryable)
            assertEquals(-17, (failure as AttestationException).errorCode)
        }

    @Test
    fun `a failure is logged with its code and without the challenge`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeClassicGateway(onRequest = {
                    _,
                    _,
                    ->
                    throw IntegrityFailure(IntegrityErrorCode.PLAY_STORE_NOT_FOUND)
                })

            runCatching { attestorFor(gateway).attest(challenge("c2VjcmV0LW5vbmNlLXZhbHVl")) }

            val record = logger.records.single()
            assertEquals(LogLevel.ERROR, record.level)
            // An exact set; see the same assertion in StandardAttestorTest for why.
            assertEquals(listOf("event", "verdictClass", "errorCode"), record.fieldNames)
            assertFalse(record.message.contains("c2VjcmV0LW5vbmNlLXZhbHVl"))
        }
}
