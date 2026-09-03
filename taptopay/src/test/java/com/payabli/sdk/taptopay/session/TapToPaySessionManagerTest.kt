package com.payabli.sdk.taptopay.session

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
            manager.settle(TapToPaySessionState.Failed(TapToPayFailureReason.ATTESTATION_REQUIRED))

            collector.cancelAndJoin()
            assertEquals(
                listOf(
                    TapToPaySessionState.Idle,
                    TapToPaySessionState.Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE),
                    TapToPaySessionState.Failed(TapToPayFailureReason.ATTESTATION_REQUIRED),
                ),
                seen,
            )
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
            TapToPaySessionState.PendingActivation -> {
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
