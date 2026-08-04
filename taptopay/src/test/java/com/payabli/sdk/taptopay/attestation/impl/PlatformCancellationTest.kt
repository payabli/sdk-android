package com.payabli.sdk.taptopay.attestation.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 10.seconds

/**
 * Which of the two things a `CancellationException` means at the platform boundary.
 *
 * `Task.await()` raises one both when the awaiting coroutine is cancelled and when the `Task` itself was
 * cancelled by whatever produced it. Forwarding both as cancellation loses the second failure rather than
 * reporting it: the caller's `catch (AttestationException)` never runs, and any handler that re-throws
 * `CancellationException` on purpose, which is the correct thing for a handler to do, swallows it outright.
 * Arming a card reader would stop with nothing raised and nothing logged.
 */
class PlatformCancellationTest {
    @Test
    fun `a cancelled task while the caller is live becomes an uncoded platform failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Nothing cancelled this coroutine, so the exception did not come from the caller.
            val outcome =
                runCatching { platformCancellation(CancellationException("Task was cancelled normally.")) }
                    .exceptionOrNull()

            assertTrue("expected an IntegrityFailure, got $outcome", outcome is IntegrityFailure)
            // Uncoded: the platform reported no error constant, and inventing one would be a fabricated
            // diagnostic. Retryable is what the mapping makes of a null, which is right for a dropped call.
            assertNull((outcome as IntegrityFailure).errorCode)
        }

    @Test
    fun `the caller's own cancellation is still a cancellation`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The other branch, and the one that must not regress: a caller that withdrew has to see
            // cancellation, not a device failure it can retry.
            val reached = CompletableDeferred<Unit>()
            var seen: Throwable? = null

            val job =
                launch {
                    try {
                        reached.complete(Unit)
                        awaitCancellation()
                    } catch (cancellation: CancellationException) {
                        seen = runCatching { platformCancellation(cancellation) }.exceptionOrNull()
                        throw cancellation
                    }
                }
            reached.await()
            job.cancelAndJoin()

            assertTrue("expected a CancellationException, got $seen", seen is CancellationException)
        }

    @Test
    fun `an expired deadline stays on the withdrawal branch rather than being reported twice`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The deadline expires by cancelling the job running the call, so the exception it produces is
            // indistinguishable from a withdrawal here and must take the same branch. If it took the other
            // one, underDeadline would never see its own expiry: the failure would surface from inside the
            // block instead, and the null-return path that reports expiry would be dead code.
            var insideBlock: Throwable? = null
            val outcome =
                runCatching {
                    underDeadline(50.milliseconds) {
                        try {
                            awaitCancellation()
                        } catch (cancellation: CancellationException) {
                            insideBlock = runCatching { platformCancellation(cancellation) }.exceptionOrNull()
                            throw cancellation
                        }
                    }
                }.exceptionOrNull()

            assertTrue(insideBlock is CancellationException)
            // And the deadline still reports itself the one way it is meant to.
            assertTrue("expected an IntegrityFailure from the deadline, got $outcome", outcome is IntegrityFailure)
            assertNull((outcome as IntegrityFailure).errorCode)
        }
}
