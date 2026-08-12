package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.PayInFlowGate
import com.payabli.example.app.payment.payInStartup
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * The first step of both card-not-present screens, at the view model rather than the derivation.
 *
 * `PaymentStepsTest` supplies the flags this writes and cannot see how they were arrived at, so
 * inverting one assignment here unlocks the form on a backend that never answered while every other
 * test stays green. That is the gap these close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenGateTest {
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    // --- storing a method ---

    @Test
    fun `a backend that answers unlocks the form`() =
        runTest {
            AnsweringServer().use { server ->
                val model = methodModel(server.target)

                model.checkToken()
                awaitCheck { model.uiState.value.isCheckingToken }

                val state = model.uiState.value
                // Readiness needs the SDK half too, which a JVM test cannot produce, so what is asserted here is
                // that the probe answered and said so. "The form unlocks" is the on-device tier.
                assertFalse("the step is still busy", state.isCheckingToken)
                assertTrue("nothing was reported: ${state.tokenCheckText}", state.tokenCheckText.startsWith("✓"))
            }
        }

    @Test
    fun `a backend that does not answer leaves the form locked`() =
        runTest {
            val model = methodModel(unreachable())

            model.checkToken()
            awaitCheck { model.uiState.value.isCheckingToken }

            val state = model.uiState.value
            assertFalse("the form unlocked on a failed check", state.backendReachable)
            assertFalse("the step is still busy", state.isCheckingToken)
            assertTrue("the failure was not reported", state.tokenCheckText.startsWith("✗"))
        }

    @Test
    fun `a failed check can be retried on the same model, and then unlocks the form`() =
        runTest {
            // One model, one address, two answers. A second model would only repeat the success
            // case: what this pins is that the first failure leaves the model able to try again.
            AnsweringServer(refuseFirst = true).use { server ->
                val model = methodModel(server.target)

                model.checkToken()
                awaitCheck { model.uiState.value.isCheckingToken }
                assertFalse("the form unlocked on the failed check", model.uiState.value.backendReachable)
                assertTrue(
                    "the failure was not reported",
                    model.uiState.value.tokenCheckText
                        .startsWith("✗"),
                )

                model.checkToken()
                awaitCheck { model.uiState.value.isCheckingToken }
                assertTrue(
                    "the retry was not reported",
                    model.uiState.value.tokenCheckText
                        .startsWith("✓"),
                )
                assertEquals("the retry never reached the endpoint", 2, server.requests)
            }
        }

    // --- taking a payment ---

    @Test
    fun `capture unlocks on a backend that answers`() =
        runTest {
            AnsweringServer().use { server ->
                val model = captureModel(server.target)

                model.checkToken()
                awaitCheck { model.uiState.value.isCheckingToken }

                assertTrue(
                    "the probe was not reported",
                    model.uiState.value.tokenCheckText
                        .startsWith("✓"),
                )
                assertFalse(model.uiState.value.isCheckingToken)
            }
        }

    @Test
    fun `capture stays locked on a backend that does not answer`() =
        runTest {
            val model = captureModel(unreachable())

            model.checkToken()
            awaitCheck { model.uiState.value.isCheckingToken }

            assertFalse("the form unlocked on a failed check", model.uiState.value.backendReachable)
            assertFalse(model.uiState.value.isCheckingToken)
            assertTrue(
                model.uiState.value.tokenCheckText
                    .startsWith("✗"),
            )
        }

    @Test
    fun `a check in flight is not started twice`() =
        runTest {
            AnsweringServer().use { server ->
                val model = methodModel(server.target)

                model.checkToken()
                model.checkToken()
                awaitCheck { model.uiState.value.isCheckingToken }

                assertEquals("the endpoint was asked twice", 1, server.requests)
            }
        }

    /**
     * Waits for the check to land.
     *
     * It suspends on a real socket off the test dispatcher, so `runTest` returns while it is still
     * in flight. Bounded, so a check that never finishes fails as that rather than as a stalled
     * suite.
     */
    private fun awaitCheck(isBusy: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse("the token check never finished", isBusy())
    }

    // --- fixtures ---

    private fun methodModel(target: TokenServerTarget) =
        PaymentMethodViewModel(
            DemoForms.storePaymentMethod(),
            startupAgainst(target),
            DiagnosticsStore(),
            diagnosticsEnabled = false,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    private fun captureModel(target: TokenServerTarget) =
        CaptureViewModel(
            DemoForms.capture(),
            startupAgainst(target),
            DiagnosticsStore(),
            diagnosticsEnabled = false,
            configuration = DemoConfiguration.fromBuildConfig(),
        )

    /**
     * The real step one against [target], with the SDK half stubbed as unavailable.
     *
     * The token probe is what this file is about, and it still runs for real. The half after it cannot: a JVM
     * test has no way to build a `PayabliPayInPaymentFlow`, whose test constructor is internal to `:payin`.
     * So `isReady` is false in every case here, and the assertions below read the text and the busy flag.
     * Readiness means "the token arrived **and** the SDK started", and only the on-device tier can produce the
     * second half.
     */
    private fun startupAgainst(target: TokenServerTarget) =
        payInStartup(
            tokenClient = TokenServerClient(target),
            gate = PayInFlowGate { Result.failure(IllegalStateException("no SDK in a JVM test")) },
        )

    /** Port 1, which refuses at once rather than waiting out a connect timeout. */
    private fun unreachable() = TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator)

    /**
     * Answers the one route with the one field the check reads.
     *
     * @param refuseFirst the first request is refused and the rest answered, so a retry against one
     *   address can be told apart from a second check against a different one.
     */
    private class AnsweringServer(
        private val refuseFirst: Boolean = false,
    ) : AutoCloseable {
        var requests = 0
            private set

        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
                createContext("/") { exchange ->
                    requests += 1
                    if (refuseFirst && requests == 1) {
                        exchange.sendResponseHeaders(503, -1)
                        exchange.close()
                        return@createContext
                    }
                    val body = """{"accessToken":"not-a-real-token"}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }

        val target = TokenServerTarget("http://127.0.0.1:${server.address.port}", TokenHostSource.Emulator)

        override fun close() = server.stop(0)
    }
}
