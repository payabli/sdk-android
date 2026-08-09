package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.diagnostics.DiagnosticsStore
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.DemoPaymentFlowController
import com.payabli.example.app.payment.PaymentFlowController
import com.payabli.example.app.payment.PaymentOperation
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.terminal.ChargeReceipt
import com.payabli.example.app.terminal.TerminalController
import com.payabli.example.app.terminal.TerminalEvent
import com.payabli.example.app.terminal.TerminalSessionState
import com.payabli.example.app.ui.capture.CaptureViewModel
import com.payabli.example.app.ui.method.PaymentMethodViewModel
import com.payabli.example.app.ui.setup.SetupViewModel
import com.payabli.example.app.ui.taptopay.TapToPayViewModel
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Nothing under test here reads the values; only which instrument they came from. */
private val cardEntry = PayInFormValues(PayInMethodType.Card, emptyMap())

private const val WAIT_MILLIS = 5_000L
private const val POLL_MILLIS = 10L

/**
 * A second callback before the first has finished must not start a second operation.
 *
 * Every one of these screens disables its button through state, which the reviewer's finding is
 * about: state disables it only once the composition has caught up, and a callback landing before
 * that reaches a function which used to accept it. Against the demo controllers that is a duplicate
 * line on a card. Behind a real SDK it is a second charge and a second stored instrument.
 *
 * The controllers here suspend until released, so the second call always lands while the first is in
 * flight. A controller that returned immediately would let the first finish and prove nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    /** Counts calls and parks in each one until [release]. */
    private class BlockingFlow : PaymentFlowController {
        val calls = AtomicInteger()
        private val waiting = mutableListOf<(Unit) -> Unit>()

        override val operation = PaymentOperation.Capture

        override val setup = DemoPaymentFlowController(PaymentOperation.Capture).setup

        override suspend fun submit(values: PayInFormValues): Result<PaymentResult> {
            calls.incrementAndGet()
            suspendCoroutine { continuation -> waiting += { continuation.resume(Unit) } }
            return Result.success(PaymentResult(code = "1"))
        }

        fun release() {
            waiting.toList().forEach { it(Unit) }
            waiting.clear()
        }
    }

    /** The same, for the terminal. Only [charge] is exercised. */
    private class BlockingTerminal : TerminalController {
        val charges = AtomicInteger()
        private val waiting = mutableListOf<(Unit) -> Unit>()

        override val sessionState: StateFlow<TerminalSessionState> = MutableStateFlow(TerminalSessionState.Ready)
        override val isReady: StateFlow<Boolean> = MutableStateFlow(true)
        override val events: Flow<TerminalEvent> = MutableSharedFlow()

        override suspend fun initialize(): Result<Unit> = Result.success(Unit)

        override suspend fun reinitializeIfNeeded(): Result<Unit> = Result.success(Unit)

        override suspend fun activateDevice(activationCode: String): Result<Unit> = Result.success(Unit)

        override suspend fun charge(amount: BigDecimal): Result<ChargeReceipt> {
            charges.incrementAndGet()
            suspendCoroutine { continuation -> waiting += { continuation.resume(Unit) } }
            return Result.success(ChargeReceipt("txn"))
        }

        fun release() {
            waiting.toList().forEach { it(Unit) }
            waiting.clear()
        }
    }

    @Test
    fun `two taps on capture submit once`() =
        runTest {
            val flow = BlockingFlow()
            val model = CaptureViewModel(flow, DiagnosticsStore(), diagnosticsEnabled = false)

            model.submit(cardEntry)
            model.submit(cardEntry)
            flow.release()

            assertEquals("submitted twice", 1, flow.calls.get())
        }

    @Test
    fun `two taps on the payment method screen submit once`() =
        runTest {
            val flow = BlockingFlow()
            val model = PaymentMethodViewModel(flow, DiagnosticsStore(), diagnosticsEnabled = false)

            model.submit(cardEntry)
            model.submit(cardEntry)
            flow.release()

            assertEquals("submitted twice", 1, flow.calls.get())
        }

    @Test
    fun `two taps on charge take one payment`() =
        runTest {
            val terminal = BlockingTerminal()
            val model = tapToPayModel(terminal)
            model.setAmount("10.00")

            model.charge()
            model.charge()
            terminal.release()

            assertEquals("charged twice", 1, terminal.charges.get())
        }

    @Test
    fun `a second operation is accepted once the first has finished`() =
        runTest {
            // The guard has to hold for one operation, not forever. A flag never cleared would pass
            // the three tests above and leave the screen dead after its first use.
            val flow = BlockingFlow()
            val model = CaptureViewModel(flow, DiagnosticsStore(), diagnosticsEnabled = false)

            model.submit(cardEntry)
            flow.release()
            model.submit(cardEntry)
            flow.release()

            assertEquals(2, flow.calls.get())
        }

    // --- the probes, which reach a socket rather than a controller ---

    /**
     * A server that answers one request at a time and not until released.
     *
     * The probes go through a concrete [TokenServerClient], so blocking them means blocking the
     * socket. Held open, a second call lands while the first is genuinely in flight, which is the
     * only arrangement under which the guard is the thing being tested.
     */
    private class BlockingServer : AutoCloseable {
        val requests = AtomicInteger()
        private val held = CountDownLatch(1)
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
                createContext("/") { exchange ->
                    requests.incrementAndGet()
                    held.await(WAIT_MILLIS, TimeUnit.MILLISECONDS)
                    val body = """{"accessToken":"tok"}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }

        val target = TokenServerTarget("http://127.0.0.1:${server.address.port}", TokenHostSource.Emulator)

        fun release() = held.countDown()

        override fun close() = server.stop(0)
    }

    /** Bounded, so a probe that never finishes fails as a stuck probe and not as a stalled suite. */
    private fun awaitIdle(isBusy: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS)
        while (isBusy() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals("the probe never finished", false, isBusy())
    }

    @Test
    fun `two taps on check token send one request`() =
        runTest {
            BlockingServer().use { server ->
                val model = tapToPayModel(BlockingTerminal(), server.target)

                model.probeToken()
                model.probeToken()
                server.release()
                awaitIdle { model.uiState.value.isWorking }

                assertEquals("probed twice", 1, server.requests.get())
            }
        }

    @Test
    fun `a later probe is accepted once the first has finished`() =
        runTest {
            // The guard has to hold for one probe, not forever.
            BlockingServer().use { server ->
                val model = tapToPayModel(BlockingTerminal(), server.target)

                model.probeToken()
                server.release()
                awaitIdle { model.uiState.value.isWorking }
                model.probeToken()
                awaitIdle { model.uiState.value.isWorking }

                assertEquals(2, server.requests.get())
            }
        }

    @Test
    fun `setup's two probes share one guard, so the second is refused`() =
        runTest {
            // This screen has two callers into the same flag, which no other screen has.
            BlockingServer().use { server ->
                val model = setupModel(server.target)

                model.probeToken()
                model.probeHealth()
                server.release()
                awaitIdle { model.uiState.value.isProbing }

                assertEquals("both probes were sent", 1, server.requests.get())
            }
        }

    @Test
    fun `setup accepts the other probe once the first has finished`() =
        runTest {
            BlockingServer().use { server ->
                val model = setupModel(server.target)

                model.probeToken()
                server.release()
                awaitIdle { model.uiState.value.isProbing }
                model.probeHealth()
                awaitIdle { model.uiState.value.isProbing }

                assertEquals(2, server.requests.get())
            }
        }

    private fun setupModel(target: TokenServerTarget) =
        SetupViewModel(
            configuration = demoConfiguration,
            tokenServer = target,
            tokenClient = TokenServerClient(target),
            readDeviceFacts = { facts },
            formConfiguration = DemoForms.storePaymentMethod().configuration,
        )

    private val demoConfiguration =
        DemoConfiguration(
            "test6",
            "com.payabli.example.app",
            "AB:CD",
            DemoEnvironment.SANDBOX,
            true,
        )

    private fun tapToPayModel(
        terminal: TerminalController,
        target: TokenServerTarget = TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator),
    ): TapToPayViewModel =
        TapToPayViewModel(
            terminal = terminal,
            tokenClient = TokenServerClient(target),
            configuration = demoConfiguration,
            readDeviceFacts = { facts },
            tokenServer = target,
        )

    private val facts =
        DeviceFacts(
            isEmulator = false,
            model = "Pixel 8",
            apiLevel = 34,
            hasNfcHardware = true,
            isNfcEnabled = true,
            playServicesInstalled = true,
            playStoreInstalled = true,
            packageName = "com.payabli.example.app",
            signingCertificateDigest = "AB:CD",
        )
}
