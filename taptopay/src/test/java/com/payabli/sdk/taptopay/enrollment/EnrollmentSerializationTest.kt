package com.payabli.sdk.taptopay.enrollment

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The coordinator's mutex, asserted rather than assumed.
 *
 * The store fake parks the first read, which puts one entry point inside the coordinator and holding the
 * lock. The other is started there, against a real second coroutine, so the overlap is constructed instead
 * of hoped for.
 *
 * Removing the mutex fails both tests here: the second caller reaches the store while the first is parked,
 * and reads a record that has not been written yet.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnrollmentSerializationTest {
    @Test
    fun `an activation started during an enrollment sends nothing until the enrollment finishes`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody()),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.ACTIVATE to listOf(activateBody()),
                    ),
                    firstReadGate = { held.await() },
                )

            val enrolling = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.enrollment.enroll() }
            assertEquals(emptyList<String>(), fixture.routes)

            val activating =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.enrollment.confirmActivation(ACTIVATION_CODE)
                }

            assertTrue(activating.isActive)
            assertEquals(emptyList<String>(), fixture.routes)

            held.complete(Unit)
            enrolling.join()
            activating.join()

            assertEquals(
                listOf(RouteScript.CHALLENGE, RouteScript.REGISTER, RouteScript.ATTEST, RouteScript.ACTIVATE),
                fixture.routes,
            )
        }

    @Test
    fun `a reset started during an enrollment does not discard the record the enrollment writes`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody()),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                    firstReadGate = { held.await() },
                )

            val enrolling = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.enrollment.enroll() }
            val resetting = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.enrollment.reset() }

            assertTrue(resetting.isActive)

            held.complete(Unit)
            enrolling.join()
            resetting.join()

            // The reset ran after the write, so the record is gone. Interleaved, it would have run before
            // the write and left one behind.
            assertEquals(
                listOf("get:$RECORD_ENTRY", "set:$RECORD_ENTRY", "remove:$RECORD_ENTRY"),
                fixture.storage.operations,
            )
        }
}
