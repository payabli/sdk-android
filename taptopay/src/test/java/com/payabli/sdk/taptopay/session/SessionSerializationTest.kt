package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.enrollment.RouteScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Long enough that a caller which is genuinely waiting has not finished, short enough to stay cheap. */
private val BLOCKED_PROBE = 300.milliseconds

/** The wall-clock ceiling once the gate is open. Generous, because this runs on shared CI hardware. */
private val COMPLETION_PROBE = 30.seconds

/**
 * That the three entry points never overlap, and that two of the same kind share one run.
 *
 * **A gate, not a race.** The store fake parks the first run inside the region and holds it there until the
 * test releases it, so the second caller genuinely arrives while the first is still in flight. Two callers
 * merely launched together almost never collide, and a test written that way passes with the serialization
 * removed.
 *
 * Exclusion and joining are separate mechanisms and have separate tests here. `region.withLock` in
 * `TapToPaySessionCoordinator.own` is what the repair, the activation and the real-thread tests hold; the
 * `RunPlan.Join` branch in `runExclusively` is what the two join tests hold. Removing either leaves the
 * other's tests green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSerializationTest {
    @Test
    fun `a repair started during a build sends nothing until the build finishes`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(SessionFixture.coldScript(), firstReadGate = { held.await() })

            val build = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            assertEquals("the build parks before it calls anything", emptyList<String>(), fixture.routes)

            val repair =
                launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.reinitializeIfNeeded() }
            assertTrue("the repair is waiting for the region", repair.isActive)
            assertEquals("the repair sent nothing while it waited", emptyList<String>(), fixture.routes)

            held.complete(Unit)
            completing("the build") { build.join() }
            completing("the repair") { repair.join() }

            assertEquals(
                listOf(RouteScript.CHALLENGE, RouteScript.REGISTER, RouteScript.ATTEST, RouteScript.CONFIG),
                fixture.routes,
            )
            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertFalse("two runs were inside the reader together", fixture.reader.sawOverlap)
            assertEquals(1, fixture.reader.prepareCount)
        }

    @Test
    fun `a second build joins the one in flight and runs nothing of its own`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(SessionFixture.coldScript(), firstReadGate = { held.await() })

            val first = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            val second = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            assertTrue("the second caller is waiting on the first", second.isActive)

            held.complete(Unit)
            completing("the first build") { first.join() }
            completing("the joining build") { second.join() }

            assertEquals(
                "one sequence, not two",
                listOf(RouteScript.CHALLENGE, RouteScript.REGISTER, RouteScript.ATTEST, RouteScript.CONFIG),
                fixture.routes,
            )
            assertEquals(1, fixture.reader.configureCount)
            assertEquals(1, fixture.reader.prepareCount)
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a joiner is told the owner withdrew, and the next caller can start`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(SessionFixture.coldScript(), firstReadGate = { held.await() })

            val owner = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            var joinerFailure: Throwable? = null
            val joiner =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.initialize()
                    } catch (failure: TapToPaySessionException) {
                        joinerFailure = failure
                    }
                }

            owner.cancel()
            completing("the joining build") { joiner.join() }

            assertTrue(
                "a joiner is never handed the owner's cancellation",
                joinerFailure is TapToPaySessionException.SetupAbandoned,
            )
            assertEquals(
                "a withdrawn run leaves nothing in progress",
                TapToPaySessionState.Idle,
                fixture.state,
            )

            // The slot was cleared, so the next caller owns rather than waiting on a claim nobody holds.
            held.complete(Unit)
            completing("the build after the withdrawal") { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    /**
     * The same exclusion, on real threads and a real clock.
     *
     * `runBlocking` and a wall-clock probe, where every other test in this file uses virtual time. Virtual
     * time cannot see a scheduler that is starved: a caller that spins holds the thread the deadline needs,
     * so the deadline never fires and the suite hangs instead of failing. Only real threads tell waiting
     * and spinning apart.
     */
    @Test
    fun `a repair genuinely waits on real threads rather than spinning`() {
        val held = CompletableDeferred<Unit>()
        val fixture = SessionFixture(SessionFixture.coldScript(), firstReadGate = { held.await() })

        runBlocking(Dispatchers.Default) {
            val build = launch { fixture.coordinator.initialize() }
            val repair = launch { fixture.coordinator.reinitializeIfNeeded() }
            try {
                assertNull(
                    "the repair ran while the build held the region",
                    withTimeoutOrNull(BLOCKED_PROBE) { repair.join() },
                )
                assertEquals("and it sent nothing while it waited", emptyList<String>(), fixture.routes)
            } finally {
                // Released here rather than after the assertions, so a failing one cannot wedge the class.
                held.complete(Unit)
            }
            withTimeout(COMPLETION_PROBE) {
                build.join()
                repair.join()
            }
        }

        assertFalse("two runs were inside the reader together", fixture.reader.sawOverlap)
        assertEquals(TapToPaySessionState.Ready, fixture.state)
    }

    @Test
    fun `an activation does not overlap a build`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(SessionFixture.coldScript(), firstReadGate = { held.await() })

            val build = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            var activationFailure: Throwable? = null
            val activation =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.confirmActivation("123456")
                    } catch (failure: Throwable) {
                        activationFailure = failure
                    }
                }
            assertTrue("the activation is waiting for the region", activation.isActive)
            assertNull("it has not run yet", activationFailure)

            held.complete(Unit)
            completing("the build") { build.join() }
            completing("the activation") { activation.join() }

            // It ran after the build rather than beside it. What it answered is the enrollment layer's
            // business; that it waited is this one's.
            assertFalse("two runs were inside the reader together", fixture.reader.sawOverlap)
        }
}
