package com.payabli.sdk.taptopay.enrollment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TURN_TIMEOUT = 5.seconds

/** A withdrawn caller, holding the turn or waiting for it, leaves the turn free and the paypoint untracked. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentTurnsTest {
    @Test
    fun `a holder withdrawn mid-turn hands the turn on and leaves nothing tracked`() =
        runTest(timeout = TURN_TIMEOUT) {
            val never = CompletableDeferred<Unit>()
            val holder =
                launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { EnrollmentTurns.taking(HOLDER_ENTRY) { never.await() } }
            assertEquals(1, EnrollmentTurns.trackedCount())

            holder.cancelAndJoin()

            assertEquals(0, EnrollmentTurns.trackedCount())
            assertEquals("next", EnrollmentTurns.taking(HOLDER_ENTRY) { "next" })
            assertEquals(0, EnrollmentTurns.trackedCount())
        }

    @Test
    fun `a waiter withdrawn in the queue leaves the holder's turn and nothing tracked`() =
        runTest(timeout = TURN_TIMEOUT) {
            val release = CompletableDeferred<Unit>()
            val holder =
                launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { EnrollmentTurns.taking(WAITER_ENTRY) { release.await() } }
            val waiter =
                launch(UnconfinedTestDispatcher(testScheduler)) { EnrollmentTurns.taking(WAITER_ENTRY) { Unit } }
            assertTrue("the waiter is queued behind the holder", waiter.isActive)

            waiter.cancelAndJoin()
            assertTrue("the holder keeps its turn", holder.isActive)
            release.complete(Unit)
            holder.join()

            assertEquals(0, EnrollmentTurns.trackedCount())
            assertEquals("next", EnrollmentTurns.taking(WAITER_ENTRY) { "next" })
            assertEquals(0, EnrollmentTurns.trackedCount())
        }

    private companion object {
        const val HOLDER_ENTRY = "turns-holder-entry"
        const val WAITER_ENTRY = "turns-waiter-entry"
    }
}
