package com.payabli.example.app.terminal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The stand-in has to walk the same sequence the real terminal will, because the Tap to pay screen's
 * button states and chip transitions are driven entirely by it. A stub that answered Ready at once
 * would leave every one of those untested here and broken on the day the SDK lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoTerminalControllerTest {
    // Zero delay: runTest's scheduler skips delays anyway, and this keeps the intent explicit.
    private fun controller() = DemoTerminalController(stepDelayMillis = 0)

    /**
     * Collects a flow for the rest of the test.
     *
     * `backgroundScope` cancels the collector when the test ends, so `runTest` is not left waiting on
     * a flow that never completes. [UnconfinedTestDispatcher] starts collection the moment this
     * returns; the default dispatcher only queues it, so the first few emissions land before anyone is
     * subscribed and the assertion sees a truncated list.
     */
    private fun <T> TestScope.collectInBackground(flow: Flow<T>): List<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(seen) }
        return seen
    }

    @Test
    fun `starts idle and not ready`() =
        runTest {
            val terminal = controller()
            assertEquals(TerminalSessionState.Idle, terminal.sessionState.value)
            assertFalse(terminal.isReady.value)
        }

    @Test
    fun `initialize walks attest then config then reader then ready`() =
        runTest {
            val terminal = controller()
            val seen = collectInBackground(terminal.sessionState)

            terminal.initialize()

            assertEquals(
                listOf(
                    TerminalSessionState.Idle,
                    TerminalSessionState.AttestingDevice,
                    TerminalSessionState.FetchingConfig,
                    TerminalSessionState.InitializingReader,
                    TerminalSessionState.Ready,
                ),
                seen,
            )
        }

    @Test
    fun `initialize emits the event codes in order`() =
        runTest {
            val terminal = controller()
            val seen = collectInBackground(terminal.events)

            terminal.initialize()

            assertEquals(
                listOf(
                    TerminalEventCode.AttestationStarted,
                    TerminalEventCode.AttestationCompleted,
                    TerminalEventCode.ConfigReceived,
                    TerminalEventCode.ReaderInitializing,
                    TerminalEventCode.ReaderReady,
                ),
                seen.map { it.code },
            )
        }

    @Test
    fun `initialize reaches ready`() =
        runTest {
            val terminal = controller()
            assertTrue(terminal.initialize().isSuccess)
            assertEquals(TerminalSessionState.Ready, terminal.sessionState.value)
        }

    @Test
    fun `reinitialize on a ready session does nothing and succeeds`() =
        runTest {
            val terminal = controller()
            terminal.initialize()
            val seen = collectInBackground(terminal.events)

            assertTrue(terminal.reinitializeIfNeeded().isSuccess)

            assertEquals(emptyList<TerminalEvent>(), seen)
        }

    @Test
    fun `reinitialize from idle runs the sequence again`() =
        runTest {
            val terminal = controller()
            assertTrue(terminal.reinitializeIfNeeded().isSuccess)
            assertEquals(TerminalSessionState.Ready, terminal.sessionState.value)
        }

    // --- charge ---

    @Test
    fun `charging zero fails, and says what to do about it`() =
        runTest {
            val terminal = controller()
            terminal.initialize()
            val result = terminal.charge(BigDecimal.ZERO)
            assertTrue(result.isFailure)
            assertEquals("Enter an amount greater than zero", result.exceptionOrNull()?.message)
        }

    @Test
    fun `charging a negative amount fails`() =
        runTest {
            val terminal = controller()
            terminal.initialize()
            assertTrue(terminal.charge(BigDecimal("-1.00")).isFailure)
        }

    @Test
    fun `charging before the terminal is ready fails`() =
        runTest {
            val terminal = controller()
            val result = terminal.charge(BigDecimal("1.00"))
            assertTrue(result.isFailure)
            assertEquals("The terminal is not ready", result.exceptionOrNull()?.message)
        }

    @Test
    fun `a successful charge returns a transaction id`() =
        runTest {
            val terminal = controller()
            terminal.initialize()
            val receipt = terminal.charge(BigDecimal("1.00")).getOrThrow()
            assertEquals("demo-txn-0001", receipt.paymentTransactionId)
        }

    @Test
    fun `each charge gets its own transaction id`() =
        runTest {
            val terminal = controller()
            terminal.initialize()
            val first = terminal.charge(BigDecimal("1.00")).getOrThrow()
            val second = terminal.charge(BigDecimal("2.00")).getOrThrow()
            assertEquals("demo-txn-0001", first.paymentTransactionId)
            assertEquals("demo-txn-0002", second.paymentTransactionId)
        }

    @Test
    fun `a charge emits initiated then nfc started then nfc completed`() =
        runTest {
            val terminal = controller()
            terminal.initialize()
            val seen = collectInBackground(terminal.events)

            terminal.charge(BigDecimal("1.00"))

            assertEquals(
                listOf(
                    TerminalEventCode.ChargeInitiated,
                    TerminalEventCode.NfcStarted,
                    TerminalEventCode.NfcCompleted,
                ),
                seen.map { it.code },
            )
        }

    // --- activation ---

    @Test
    fun `an ordinary activation code succeeds`() =
        runTest {
            val terminal = controller()
            assertTrue(terminal.activateDevice("ABC123").isSuccess)
        }

    @Test
    fun `the sentinel code fails, so the error path is reachable from the screen`() =
        runTest {
            val terminal = controller()
            val result = terminal.activateDevice(DemoTerminalController.REJECTED_ACTIVATION_CODE)
            assertTrue(result.isFailure)
        }

    @Test
    fun `the sentinel is matched ignoring case and surrounding space`() =
        runTest {
            val terminal = controller()
            assertTrue(terminal.activateDevice("  reject  ").isFailure)
        }

    @Test
    fun `a rejected activation emits started then failed`() =
        runTest {
            val terminal = controller()
            val seen = collectInBackground(terminal.events)

            terminal.activateDevice(DemoTerminalController.REJECTED_ACTIVATION_CODE)

            assertEquals(
                listOf(TerminalEventCode.ActivationStarted, TerminalEventCode.ActivationFailed),
                seen.map { it.code },
            )
        }
}
