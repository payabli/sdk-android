package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The machine on its own: what it publishes, what it refuses, and which of those two it throws for.
 *
 * The rule under test is that no mutator hands back a value a caller can drop. The sibling SDK returns a
 * boolean from its transition and discards it at every call site, and the cost was a repair that ran every
 * phase and moved the state nowhere.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TapToPaySessionManagerTest {
    private val logger = RecordingSdkLogger()
    private val manager = TapToPaySessionManager(logger)

    @Test
    fun `a session starts at the beginning`() {
        assertEquals(TapToPaySessionState.Idle, manager.state.value)
    }

    @Test
    fun `a move a collector makes is reported after the move that woke it`() =
        runTest(timeout = TEST_TIMEOUT) {
            manager.advance(TapToPaySessionState.FetchingConfig)
            manager.advance(TapToPaySessionState.InitializingReader)

            val reported = mutableListOf<String>()
            TelemetryRecorders.install { _, properties ->
                reported += "${properties["from"]}->${properties["to"]}"
            }
            try {
                val collector =
                    launch(UnconfinedTestDispatcher(testScheduler)) {
                        manager.state.collect { state ->
                            if (state == TapToPaySessionState.Ready) manager.invalidate()
                        }
                    }

                manager.advance(TapToPaySessionState.Ready)
                collector.cancelAndJoin()

                assertEquals(
                    listOf("initializing_reader->ready", "ready->session_expired"),
                    reported,
                )
            } finally {
                TelemetryRecorders.clear()
            }
        }

    @Test
    fun `a collector handed a state finds readiness already agreeing with it`() =
        runTest(timeout = TEST_TIMEOUT) {
            manager.advance(TapToPaySessionState.FetchingConfig)
            manager.advance(TapToPaySessionState.InitializingReader)

            // An unconfined collector resumes inside the state write, so this reads the pair at the one
            // moment they can disagree.
            val disagreements = mutableListOf<String>()
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    manager.state.collect { state ->
                        val ready = manager.isReady.value
                        if (ready != (state == TapToPaySessionState.Ready)) {
                            disagreements += "$state with isReady=$ready"
                        }
                    }
                }

            manager.advance(TapToPaySessionState.Ready)
            collector.cancelAndJoin()

            assertEquals(emptyList<String>(), disagreements)
        }

    @Test
    fun `readiness cannot be left disagreeing with the state that set it`() =
        runTest(timeout = TEST_TIMEOUT) {
            manager.advance(TapToPaySessionState.FetchingConfig)
            manager.advance(TapToPaySessionState.InitializingReader)

            // A second writer landing between the state write and the readiness write. An unconfined
            // collector resumes inside the state write, which is that window exactly and reaches it
            // without threads or timing: the reader invalidates from in there, so the two writes to
            // readiness are ordered the wrong way round unless they were committed together.
            val collector =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    manager.state.collect { state ->
                        if (state == TapToPaySessionState.Ready) manager.invalidate()
                    }
                }

            manager.advance(TapToPaySessionState.Ready)
            collector.cancelAndJoin()

            assertEquals(TapToPaySessionState.SessionExpired, manager.state.value)
            assertFalse(
                "state is ${manager.state.value} and isReady is ${manager.isReady.value}",
                manager.isReady.value,
            )
        }

    @Test
    fun `a refused move throws before the work under it runs`() =
        runTest(timeout = TEST_TIMEOUT) {
            manager.advance(TapToPaySessionState.FetchingConfig)
            manager.advance(TapToPaySessionState.InitializingReader)
            manager.advance(TapToPaySessionState.Ready)

            var ran = false
            val failure =
                runCatching {
                    // Not reachable from ready: a session that is up does not go back to fetching.
                    manager.advance(TapToPaySessionState.FetchingConfig) { ran = true }
                }.exceptionOrNull()

            assertTrue("$failure", failure is IllegalStateException)
            assertFalse("the work ran under a state that was never published", ran)
            assertEquals(TapToPaySessionState.Ready, manager.state.value)
        }

    @Test
    fun `a phase that fails leaves the state where the phase was`() =
        runTest(timeout = TEST_TIMEOUT) {
            class PhaseFailed : Exception()

            val failure =
                runCatching {
                    manager.advance(TapToPaySessionState.AttestingDevice) { throw PhaseFailed() }
                }.exceptionOrNull()

            assertTrue("$failure", failure is PhaseFailed)
            assertEquals(
                "landing a failure belongs to the coordinator, not here",
                TapToPaySessionState.AttestingDevice,
                manager.state.value,
            )
        }

    @Test
    fun `every state can start over`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (state in EVERY_SESSION_STATE) {
                val fresh = TapToPaySessionManager(logger)
                driveTo(fresh, state)
                assertEquals(state.diagnosticName, state, fresh.state.value)

                fresh.reset()

                assertEquals(state.diagnosticName, TapToPaySessionState.Idle, fresh.state.value)
            }
        }

    @Test
    fun `a stale reader report is dropped rather than expiring a session that is not ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            manager.advance(TapToPaySessionState.FetchingConfig)

            manager.invalidate()

            assertEquals(TapToPaySessionState.FetchingConfig, manager.state.value)
            assertTrue(
                "a dropped report is recorded, naming both ends of the move it refused",
                logger.records.any { it.fieldNames.containsAll(listOf("fromstate", "tostate")) },
            )
        }

    @Test
    fun `a ready session is expired by a reader report`() =
        runTest(timeout = TEST_TIMEOUT) {
            manager.advance(TapToPaySessionState.FetchingConfig)
            manager.advance(TapToPaySessionState.InitializingReader)
            manager.advance(TapToPaySessionState.Ready)

            manager.invalidate()

            assertEquals(TapToPaySessionState.SessionExpired, manager.state.value)
        }

    @Test
    fun `a refused move is never briefly published`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<TapToPaySessionState>()
            // Unconfined, so a collector resumes inside the write if one is made.
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.collect { seen += it } }

            manager.advance(TapToPaySessionState.FetchingConfig)
            runCatching { manager.advance(TapToPaySessionState.Ready) }
            manager.invalidate()

            collector.cancelAndJoin()
            assertEquals(
                listOf(TapToPaySessionState.Idle, TapToPaySessionState.FetchingConfig),
                seen,
            )
        }

    @Test
    fun `re-entering a state publishes nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<TapToPaySessionState>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.collect { seen += it } }

            manager.advance(TapToPaySessionState.FetchingConfig)
            manager.advance(TapToPaySessionState.FetchingConfig)

            collector.cancelAndJoin()
            assertEquals(listOf(TapToPaySessionState.Idle, TapToPaySessionState.FetchingConfig), seen)
        }

    @Test
    fun `a failure publishes again when only its reason changed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<TapToPaySessionState>()
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.collect { seen += it } }

            manager.settle(TapToPaySessionState.Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE))
            manager.settle(TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED))

            collector.cancelAndJoin()
            assertEquals(
                listOf(
                    TapToPaySessionState.Idle,
                    TapToPaySessionState.Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE),
                    TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED),
                ),
                seen,
            )
        }

    private class NotReady : Exception()

    @Test
    fun `a charge is refused unless the session is ready, and moves nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (state in EVERY_SESSION_STATE.filter { it != TapToPaySessionState.Ready }) {
                val fresh = TapToPaySessionManager(logger)
                driveTo(fresh, state)
                var ran = false

                val failure = runCatching { fresh.charging({ NotReady() }) { ran = true } }.exceptionOrNull()

                assertTrue(state.diagnosticName, failure is NotReady)
                assertFalse(state.diagnosticName, ran)
                assertEquals(state.diagnosticName, state, fresh.state.value)
            }
        }

    @Test
    fun `a charge holds the session while it runs and gives it back when it ends`() =
        runTest(timeout = TEST_TIMEOUT) {
            driveTo(manager, TapToPaySessionState.Ready)
            var during: Pair<TapToPaySessionState, Boolean>? = null

            manager.charging({ NotReady() }) { during = manager.state.value to manager.isReady.value }

            assertEquals(TapToPaySessionState.Charging(TapToPayChargeActivity.OPENING) to false, during)
            assertEquals(TapToPaySessionState.Ready, manager.state.value)
            assertTrue(manager.isReady.value)
        }

    @Test
    fun `a charge that throws still gives the session back`() =
        runTest(timeout = TEST_TIMEOUT) {
            driveTo(manager, TapToPaySessionState.Ready)

            runCatching { manager.charging({ NotReady() }) { throw IllegalArgumentException("refused") } }

            assertEquals(TapToPaySessionState.Ready, manager.state.value)
        }

    @Test
    fun `a session moved away during a charge is left where it was moved`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The last is a setup that started over during the charge and is preparing the reader again, from
            // where ready is one legal move away.
            val moves =
                mapOf<TapToPaySessionState, (TapToPaySessionManager) -> Unit>(
                    TapToPaySessionState.Idle to { it.reset() },
                    TapToPaySessionState.SessionExpired to { it.invalidate() },
                    TapToPaySessionState.InitializingReader to {
                        it.reset()
                        it.advance(TapToPaySessionState.FetchingConfig)
                        it.advance(TapToPaySessionState.InitializingReader)
                    },
                )
            for ((moved, move) in moves) {
                val fresh = TapToPaySessionManager(logger)
                driveTo(fresh, TapToPaySessionState.Ready)

                fresh.charging({ NotReady() }) {
                    move(fresh)
                    fresh.chargeActivity(TapToPayChargeActivity.WAITING_FOR_CARD)
                }

                assertEquals(moved.diagnosticName, moved, fresh.state.value)
            }
        }

    @Test
    fun `a charge expiring the session expires it only while the charge holds it`() =
        runTest(timeout = TEST_TIMEOUT) {
            driveTo(manager, TapToPaySessionState.Ready)
            manager.expireCharge()
            assertEquals(TapToPaySessionState.Ready, manager.state.value)

            manager.charging({ NotReady() }) { manager.expireCharge() }
            assertEquals(TapToPaySessionState.SessionExpired, manager.state.value)
        }

    @Test
    fun `an activity outside a charge is dropped`() {
        manager.chargeActivity(TapToPayChargeActivity.WAITING_FOR_CARD)

        assertEquals(TapToPaySessionState.Idle, manager.state.value)
    }

    @Test
    fun `a move between activities is published and not reported`() =
        runTest(timeout = TEST_TIMEOUT) {
            driveTo(manager, TapToPaySessionState.Ready)
            val reported = mutableListOf<String>()
            TelemetryRecorders.install { _, properties -> reported += "${properties["from"]}->${properties["to"]}" }
            try {
                manager.charging({ NotReady() }) {
                    manager.chargeActivity(TapToPayChargeActivity.WAITING_FOR_CARD)
                    assertEquals(
                        TapToPaySessionState.Charging(TapToPayChargeActivity.WAITING_FOR_CARD),
                        manager.state.value,
                    )
                }

                assertEquals(listOf("ready->charging", "charging->ready"), reported)
            } finally {
                TelemetryRecorders.clear()
            }
        }

    /**
     * Walks a fresh machine to [target] through legal moves only.
     *
     * Seeding the field directly would let this test pass with the table broken, which is the one thing it
     * must not do.
     */
    private suspend fun driveTo(
        manager: TapToPaySessionManager,
        target: TapToPaySessionState,
    ) {
        when (target) {
            TapToPaySessionState.Idle -> Unit
            TapToPaySessionState.AttestingDevice -> manager.advance(target)
            TapToPaySessionState.FetchingConfig -> manager.advance(target)
            is TapToPaySessionState.PendingActivation -> {
                manager.advance(TapToPaySessionState.FetchingConfig)
                manager.advance(target)
            }

            TapToPaySessionState.InitializingReader -> {
                manager.advance(TapToPaySessionState.FetchingConfig)
                manager.advance(target)
            }

            TapToPaySessionState.Ready -> {
                driveTo(manager, TapToPaySessionState.InitializingReader)
                manager.advance(target)
            }

            is TapToPaySessionState.Charging -> {
                driveTo(manager, TapToPaySessionState.Ready)
                manager.advance(target)
            }

            TapToPaySessionState.SessionExpired -> {
                driveTo(manager, TapToPaySessionState.Ready)
                manager.invalidate()
            }

            TapToPaySessionState.Reinitializing -> {
                driveTo(manager, TapToPaySessionState.SessionExpired)
                manager.advance(target)
            }

            is TapToPaySessionState.Failed -> manager.settle(target)
        }
    }
}
