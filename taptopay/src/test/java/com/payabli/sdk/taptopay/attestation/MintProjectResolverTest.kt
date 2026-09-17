package com.payabli.sdk.taptopay.attestation

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.taptopay.attestation.impl.ClassicAttestor
import com.payabli.sdk.taptopay.attestation.impl.FakeClassicGateway
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * A mint that belongs to one challenge must keep that challenge's project when another enrollment
 * overwrites the environment store before the mint runs.
 */
class MintProjectResolverTest {
    @Test
    fun `a pinned mint keeps its project when the store is overwritten`() =
        runTest(timeout = TEST_TIMEOUT) {
            val projects = AttestationProjectStore(FakeSecureStore())
            val mintProject = MintProjectResolver { projects.require(PayabliEnvironment.SANDBOX) }
            val gateway = FakeClassicGateway()
            val attestor = ClassicAttestor(gateway, cloudProjectNumber = { mintProject.resolve() })

            projects.remember(PayabliEnvironment.SANDBOX, 111L)
            mintProject.whilePinned(111L) {
                projects.remember(PayabliEnvironment.SANDBOX, 222L)
                attestor.attest(AttestationChallenge.classic("c2VydmVyLWlzc3VlZC1ub25jZQ"))
            }

            assertEquals(listOf(111L), gateway.cloudProjectNumbers)
            assertEquals(222L, projects.require(PayabliEnvironment.SANDBOX))
        }

    @Test
    fun `without a pin the mint reads the environment store`() =
        runTest(timeout = TEST_TIMEOUT) {
            val projects = AttestationProjectStore(FakeSecureStore())
            val mintProject = MintProjectResolver { projects.require(PayabliEnvironment.SANDBOX) }
            val gateway = FakeClassicGateway()
            val attestor = ClassicAttestor(gateway, cloudProjectNumber = { mintProject.resolve() })

            projects.remember(PayabliEnvironment.SANDBOX, 333L)
            attestor.attest(AttestationChallenge.classic("c2VydmVyLWlzc3VlZC1ub25jZQ"))

            assertEquals(listOf(333L), gateway.cloudProjectNumbers)
        }

    @Test
    fun `a failing pinned block clears the pin for the next enrollment`() =
        runTest(timeout = TEST_TIMEOUT) {
            val mintProject = MintProjectResolver { 333L }

            runCatching {
                mintProject.whilePinned(111L) {
                    error("registration failed")
                }
            }

            assertEquals(333L, mintProject.resolve())
            mintProject.whilePinned(222L) {
                assertEquals(222L, mintProject.resolve())
            }
            assertEquals(333L, mintProject.resolve())
        }
}
