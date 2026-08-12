package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The budget under test, named so the bound below is derived from it rather than tracking it. */
internal const val CALL_BUDGET_MILLIS = 200L

/** Well above the budget and well below the socket read timeout, so the budget is provably what fired. */
internal const val CALL_BUDGET_STALL_MILLIS = 800L

/**
 * The midpoint, which is what "nearer the budget than the stall" means.
 *
 * A tighter bound catches nothing extra, since the behaviour it guards against is waiting out the whole
 * stall.
 */
internal const val CALL_BUDGET_CUTOFF_MILLIS = (CALL_BUDGET_MILLIS + CALL_BUDGET_STALL_MILLIS) / 2

/**
 * How many times the cut-off is offered a fair scheduler before the run is called a defect.
 *
 * Three because the two outcomes separate on repetition rather than on any single number. A deadline that
 * has stopped tearing the socket down waits out the stall on every attempt, so the count does not rescue
 * it; a machine that missed the deadline missed it for as long as it was starved, which is intermittent.
 */
private const val CALL_BUDGET_ATTEMPTS = 3

/** A budget no stall can exhaust, for the control measurement in the failure message. */
private val UNBUDGETED = (CALL_BUDGET_STALL_MILLIS * 10).milliseconds

/**
 * The whole-call bound, which no socket-level timeout provides: the read timeout only ever bounds the wait
 * for the next byte.
 *
 * **Asserts the elapsed time, not only the error.** An earlier version checked the error alone and passed at
 * 810ms against a 200ms budget, because the call waited out the whole stall and failed afterwards. That is
 * what a test looks like when the mechanism it covers does not work.
 *
 * **And it asserts that over attempts rather than once.** Elapsed time is the only observable that separates
 * a cut-off from a completed stall, and on a shared emulator it is not a reliable one: measured on the
 * nightly's own image under CPU load, the same cut-off landed at 591ms, 1195ms and 1230ms across 40 runs
 * that were otherwise green, one of which turned a nightly red at 1115ms. The deadline, the blocking read
 * and the stalling server all slip together when nothing is scheduled on time. Repetition tells the two
 * apart, since a deadline that no longer fires is late on every attempt and a starved machine is not.
 *
 * [transportFor] builds the transport for one server and one budget, which is the only part that differs
 * between the JVM and instrumented copies of this claim.
 */
internal suspend fun assertTheCallBudgetCutsTheCallOutOfTheStall(
    transportFor: (LoopbackServer, Duration) -> PayabliTransport,
) {
    val late = mutableListOf<Long>()
    var neverReachedTheServer = 0

    repeat(CALL_BUDGET_ATTEMPTS) {
        LoopbackServer().use { server ->
            server.respondWith(200, "").stallBeforeResponding(CALL_BUDGET_STALL_MILLIS)

            val startedAt = System.currentTimeMillis()
            val thrown = server.callAndCatch(transportFor, CALL_BUDGET_MILLIS.milliseconds)
            val elapsed = System.currentTimeMillis() - startedAt

            // Both hold however the machine is behaving, so every attempt asserts them.
            assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
            assertEquals(PayabliErrorCode.NETWORK_ERROR, (thrown as PayabliException).code)

            // Whether the budget ended a call in flight rather than one that never began. On a starved
            // machine the deadline can expire before the request reaches the wire, and an attempt that never
            // put a call in flight cannot show a call being cut out of one: 6 of 40 loaded runs went this
            // way. Counted and retried, so a deadline that fires before every request still fails below
            // rather than passing as a cut-off it never demonstrated.
            val request = server.awaitOnlyRequestOrNull()
            if (request == null) {
                neverReachedTheServer++
                return@repeat
            }
            assertEquals("/api/ping", request.path)

            if (elapsed < CALL_BUDGET_CUTOFF_MILLIS) return
            late += elapsed
        }
    }

    // No attempt showed the cut-off, so the run is red either way. Which of the two it is decides who looks
    // at it, so measure the machine as well: the same stall under a budget it cannot exhaust. A control near
    // the stall means the deadline is the thing that stopped working; a control far past it means nothing on
    // this machine was on time, the cut-off included.
    val lateAt = if (late.isEmpty()) "none" else late.joinToString("ms, ", postfix = "ms")
    fail(
        "the call was never cut off out of the stall across $CALL_BUDGET_ATTEMPTS attempts: " +
            "${late.size} ran late at $lateAt against a ${CALL_BUDGET_CUTOFF_MILLIS}ms bound on a " +
            "${CALL_BUDGET_MILLIS}ms budget, and $neverReachedTheServer never reached the server at all; " +
            "the ${CALL_BUDGET_STALL_MILLIS}ms stall took ${measureUnbudgetedStall(transportFor)}ms to " +
            "wait out unbudgeted on this machine",
    )
}

/** The stall as this machine serves it, with nothing cutting the call short. */
private suspend fun measureUnbudgetedStall(transportFor: (LoopbackServer, Duration) -> PayabliTransport): Long =
    LoopbackServer().use { server ->
        server.respondWith(200, "").stallBeforeResponding(CALL_BUDGET_STALL_MILLIS)
        val startedAt = System.currentTimeMillis()
        // Discarded: this measures the machine, and a control that failed still measured it.
        server.callAndCatch(transportFor, UNBUDGETED)
        System.currentTimeMillis() - startedAt
    }

private suspend fun LoopbackServer.callAndCatch(
    transportFor: (LoopbackServer, Duration) -> PayabliTransport,
    budget: Duration,
): Throwable? =
    runCatching {
        transportFor(this, budget).execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
    }.exceptionOrNull()
