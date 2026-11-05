package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.decline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failure this SDK does not classify, raised while the configuration is fetched. */
class UnexaminedFailureLandingTest {
    @Test
    fun `a failure that is not the SDK's own type in the configuration is the SDK's defect, named by type`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    SessionFixture.coldScript(),
                    unsent = { request ->
                        if (request.path == RouteScript.CONFIG) IllegalStateException(SENTINEL) else null
                    },
                )

            val failure =
                runCatching { fixture.coordinator.initialize() }.exceptionOrNull()
                    ?: throw AssertionError("expected initialize to fail")

            assertEquals(PayabliErrorType.SDK_INTERNAL_ERROR, TapToPayErrorCodes.typeFor(failure))
            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.SDK_INTERNAL_ERROR), fixture.state)
            val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(chain.toString(), chain.any { it.message == IllegalStateException::class.java.name })
            assertFalse(chain.toString(), chain.any { it.message.orEmpty().contains(SENTINEL) })
        }

    @Test
    fun `a failure that is not the SDK's own type in an activation is the SDK's defect, named by type`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CONFIG to listOf(decline(403, "Device is not active."), configBody()),
                    ),
                    unsent = { request ->
                        if (request.path == RouteScript.ACTIVATE) IllegalStateException(SENTINEL) else null
                    },
                )
            fixture.seedRecord()
            runCatching { fixture.coordinator.initialize() }

            val failure =
                runCatching { fixture.coordinator.activateDevice("123456") }.exceptionOrNull()
                    ?: throw AssertionError("expected the activation to fail")

            assertEquals(PayabliErrorType.SDK_INTERNAL_ERROR, TapToPayErrorCodes.typeFor(failure))
            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.SDK_INTERNAL_ERROR), fixture.state)
            val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertFalse(chain.toString(), chain.any { it.message.orEmpty().contains(SENTINEL) })
        }

    private companion object {
        const val SENTINEL = "sentinel 4111 1111 1111 1111"
    }
}
