package com.payabli.sdk.taptopay.attestation

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.taptopay.attestation.impl.ClassicAttestor
import com.payabli.sdk.taptopay.attestation.impl.FAKE_CLOUD_PROJECT
import com.payabli.sdk.taptopay.attestation.impl.FakeClassicGateway
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The gap the store exists for: a challenge that carried a number, then a mint with no response in hand.
 */
class AttestationProjectMintGapTest {
    @Test
    fun `a later classic mint uses the number remembered from a challenge`() =
        runTest(timeout = TEST_TIMEOUT) {
            val projects = AttestationProjectStore(FakeSecureStore())
            val gateway = FakeClassicGateway()
            val attestor =
                ClassicAttestor(gateway, cloudProjectNumber = { projects.require(PayabliEnvironment.SANDBOX) })

            projects.rememberFromChallenge(PayabliEnvironment.SANDBOX, FAKE_CLOUD_PROJECT.toString())
            attestor.attest(AttestationChallenge.classic("c2VydmVyLWlzc3VlZC1ub25jZQ"))

            assertEquals(listOf(FAKE_CLOUD_PROJECT), gateway.cloudProjectNumbers)
        }

    @Test
    fun `a mint with nothing stored never reaches the gateway`() =
        runTest(timeout = TEST_TIMEOUT) {
            val projects = AttestationProjectStore(FakeSecureStore())
            val gateway = FakeClassicGateway()
            val attestor =
                ClassicAttestor(gateway, cloudProjectNumber = { projects.require(PayabliEnvironment.SANDBOX) })

            val failure =
                runCatching {
                    attestor.attest(AttestationChallenge.classic("c2VydmVyLWlzc3VlZC1ub25jZQ"))
                }.exceptionOrNull()

            assertTrue(failure is AttestationException.Misconfigured)
            assertTrue(gateway.nonces.isEmpty())
            assertTrue(gateway.cloudProjectNumbers.isEmpty())
        }
}
