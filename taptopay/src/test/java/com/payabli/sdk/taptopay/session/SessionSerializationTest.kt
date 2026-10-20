package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.attestation.device.ActivateRequest
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.activateBody
import com.payabli.sdk.taptopay.enrollment.decline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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

/** An `Error`, so the coordinator's fatal path is reached without an `OutOfMemoryError` in a test. */
private class FatalTestError : Error()

/** The 403 that leaves a session waiting for a code, from a stored binding. */
private const val NOT_ACTIVE = "Device is not active."

/**
 * That the three entry points never overlap, and what a second caller gets: a run it joins, or a turn it
 * waits for. A gate, not a race: the fixture parks the first run and holds it there until the test
 * releases it, so a second caller genuinely arrives while the first is in flight.
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
    fun `a queued call withdrawing does not reset the state of the one running`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A caller cancelled while waiting for the region never started anything, so there is nothing of
            // its own to settle. Settling anyway reaches the run that does hold the region, and puts the
            // session somewhere the running work cannot move on from.
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(SessionFixture.coldScript(), readerGate = { held.await() })

            val build = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            val queued =
                launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.activateDevice("123456") }
            assertTrue("the activation is waiting for the region", queued.isActive)

            queued.cancelAndJoin()

            held.complete(Unit)
            completing("the build") { build.join() }

            assertEquals("the withdrawal reset a session it never owned", TapToPaySessionState.Ready, fixture.state)
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
            // Eligibility included. It is the one phase that announces no state, so it is also the one a
            // reader can mistake for running outside the region; it runs first inside the same lock.
            assertEquals(1, fixture.reader.eligibilityCount)
            assertEquals(1, fixture.reader.configureCount)
            assertEquals(1, fixture.reader.prepareCount)
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a build joins the build in flight even while a repair is queued behind it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(SessionFixture.coldScript(), firstReadGate = { held.await() })

            val first = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }
            // A caller of another kind arrives between the two builds and waits for the region.
            val repair =
                launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.reinitializeIfNeeded() }
            val second = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }

            held.complete(Unit)
            completing("the first build") { first.join() }
            completing("the queued repair") { repair.join() }
            completing("the second build") { second.join() }

            assertEquals(
                "one sequence, not two",
                listOf(RouteScript.CHALLENGE, RouteScript.REGISTER, RouteScript.ATTEST, RouteScript.CONFIG),
                fixture.routes,
            )
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

            owner.cancelAndJoin()
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

            // The slot was cleared, so the next caller owns the run.
            held.complete(Unit)
            completing("the build after the withdrawal") { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a joiner is told the owner failed when the owner died on something unclassified`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    SessionFixture.coldScript(),
                    firstReadGate = {
                        held.await()
                        throw FatalTestError()
                    },
                )

            var ownerFailure: Throwable? = null
            val owner =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.initialize()
                    } catch (fatal: Throwable) {
                        ownerFailure = fatal
                    }
                }
            var joinerFailure: Throwable? = null
            val joiner =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.initialize()
                    } catch (failure: TapToPaySessionException) {
                        joinerFailure = failure
                    }
                }

            held.complete(Unit)
            completing("the owner") { owner.join() }
            completing("the joining build") { joiner.join() }

            assertTrue("the owner keeps what killed it", ownerFailure is FatalTestError)
            assertTrue(
                "a joiner is told the run failed, not that it withdrew",
                joinerFailure is TapToPaySessionException.SetupFailed,
            )
            assertNull("the cause stays with the owner", joinerFailure?.cause)
        }

    /**
     * The same exclusion, on real threads and a real clock.
     *
     * `runBlocking` and a wall-clock probe, where every other test in this file uses virtual time. Virtual
     * time cannot see a scheduler that is starved: a caller that spins holds the thread the deadline needs,
     * so the deadline never fires and the suite hangs. Only real threads tell waiting and spinning apart.
     *
     * The repair is launched only once the build is inside the region, because on real threads nothing orders
     * two launches. A repair that got there first would enter from `Idle`, reach the credentials with nothing
     * attested and throw, which is the failure this had on CI twice.
     */
    @Test
    fun `a repair genuinely waits on real threads rather than spinning`() {
        val entered = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val fixture =
            SessionFixture(
                SessionFixture.coldScript(),
                firstReadGate = {
                    entered.complete(Unit)
                    held.await()
                },
            )

        runBlocking(Dispatchers.Default) {
            val build = launch { fixture.coordinator.initialize() }
            withTimeout(COMPLETION_PROBE) { entered.await() }
            val repair = launch { fixture.coordinator.reinitializeIfNeeded() }
            try {
                assertNull(
                    "the repair ran while the build held the region",
                    withTimeoutOrNull(BLOCKED_PROBE) { repair.join() },
                )
                assertEquals("and it sent nothing while it waited", emptyList<String>(), fixture.routes)
            } finally {
                // Released before the assertions run, so a failing one cannot wedge the class.
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
                        fixture.coordinator.activateDevice("123456")
                    } catch (failure: Throwable) {
                        activationFailure = failure
                    }
                }
            assertTrue("the activation is waiting for the region", activation.isActive)
            assertNull("it has not run yet", activationFailure)

            held.complete(Unit)
            completing("the build") { build.join() }
            completing("the activation") { activation.join() }

            // It ran after the build. What it answered is the enrollment layer's business; that it waited
            // is this one's.
            assertFalse("two runs were inside the reader together", fixture.reader.sawOverlap)
        }

    /** A session waiting for a code, with the first activation parked at its send. */
    private fun pendingSession(
        activateAnswers: List<String>,
        held: CompletableDeferred<Unit>,
    ): SessionFixture {
        val fixture =
            SessionFixture(
                RouteScript(
                    RouteScript.CONFIG to listOf(decline(403, NOT_ACTIVE)),
                    RouteScript.ACTIVATE to activateAnswers,
                ),
                sendGate = { request -> if (request.path == RouteScript.ACTIVATE) held.await() },
            )
        fixture.seedRecord()
        return fixture
    }

    /** The code an activation request carried, read back off the wire rather than off a call site. */
    private fun carriedCode(request: PayabliRequest): String =
        PayabliJson.format
            .decodeFromString(
                ActivateRequest.serializer(),
                request.body?.toString(Charsets.UTF_8) ?: error("the activation request carried no body"),
            ).activationCode

    @Test
    fun `a second activation with the same code joins the one in flight`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = pendingSession(listOf(activateBody()), held)
            runCatching { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)

            val first =
                launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.activateDevice("111111") }
            assertEquals(
                "the first activation has left and has no answer",
                1,
                fixture.requestsTo(RouteScript.ACTIVATE).size,
            )
            val second =
                launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.activateDevice("111111") }
            assertTrue("the second caller is waiting on the first", second.isActive)

            held.complete(Unit)
            completing("the first activation") { first.join() }
            completing("the joining activation") { second.join() }

            assertEquals("a double tap spends one attempt", 1, fixture.requestsTo(RouteScript.ACTIVATE).size)
            assertEquals(TapToPaySessionState.Idle, fixture.state)
        }

    @Test
    fun `a second activation with a different code waits its turn and sends its own code`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                pendingSession(listOf(decline(400, "Invalid activation code."), activateBody()), held)
            runCatching { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)

            var refused: Throwable? = null
            val first =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.activateDevice("111111")
                    } catch (failure: Throwable) {
                        refused = failure
                    }
                }
            assertEquals(
                "the first activation has left and has no answer",
                1,
                fixture.requestsTo(RouteScript.ACTIVATE).size,
            )
            var secondFailure: Throwable? = null
            val second =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.activateDevice("222222")
                    } catch (failure: Throwable) {
                        secondFailure = failure
                    }
                }
            assertTrue("the corrected code is waiting its turn", second.isActive)
            assertEquals("nothing was sent for it while it waited", 1, fixture.requestsTo(RouteScript.ACTIVATE).size)

            held.complete(Unit)
            completing("the refused activation") { first.join() }
            completing("the corrected activation") { second.join() }

            assertEquals(
                PayabliErrorType.ACTIVATION_CODE_INCORRECT,
                TapToPayErrorCodes.typeFor(refused ?: error("the first activation was not refused")),
            )
            assertNull("the corrected code was answered for itself, not for the refused one", secondFailure)
            assertEquals(
                "each code was sent, each once",
                listOf("111111", "222222"),
                fixture.requestsTo(RouteScript.ACTIVATE).map(::carriedCode),
            )
            assertEquals(TapToPaySessionState.Idle, fixture.state)
        }

    @Test
    fun `a second activation with a different code is refused without sending once the device is active`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = pendingSession(listOf(activateBody()), held)
            runCatching { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)

            val first =
                launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.activateDevice("111111") }
            var refused: Throwable? = null
            val second =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    try {
                        fixture.coordinator.activateDevice("222222")
                    } catch (failure: Throwable) {
                        refused = failure
                    }
                }
            assertTrue("the second activation is waiting its turn", second.isActive)

            held.complete(Unit)
            completing("the first activation") { first.join() }
            completing("the refused activation") { second.join() }

            assertEquals(
                PayabliErrorType.DEVICE_NOT_PENDING,
                TapToPayErrorCodes.typeFor(refused ?: error("the second activation was not refused")),
            )
            assertEquals(
                "the active device refused it before anything was sent",
                1,
                fixture.requestsTo(RouteScript.ACTIVATE).size,
            )
            assertEquals(TapToPaySessionState.Idle, fixture.state)
        }
}
