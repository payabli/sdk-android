package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.payabli.sdk.taptopay.attestation.AttestationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long a throttled attestor refuses to call the platform again.
 *
 * **A floor that stops a loop, not a model of the budget, and the difference matters.** The platform
 * publishes no retry-after for this, no per-minute or per-device rate limit, and it reports short-term
 * throttling and daily exhaustion with the same code. So no value here can be derived, and none can be
 * correct for both regimes.
 *
 * **This deliberately does not follow the vendor's stated resolution, which is "Retry with an exponential
 * backoff".** That advice is scoped in the same document to "operations that happen in the background and
 * don't affect the user experience while the user is in session", with acknowledging a purchase as the
 * worked example. Arming a card reader is the opposite: a person is waiting, and the prescribed schedule
 * is five, ten and twenty seconds before concluding.
 *
 * The same note says to retry transient conditions and **not** to retry conditions that are not transient.
 * The documented meaning of this code is "has been throttled, or your app has exceeded its daily request
 * quota", and nothing distinguishes the two at the client. So the rule cannot be applied where the error
 * arrives: one branch is worth waiting out and the other is not, and only whatever issues challenges can
 * see which. Note also where the vendor's own path ends, after three attempts: treat the outcome as a
 * failed integrity check. Refusing here reaches the same place without spending the requests.
 *
 * What is measured, stated as loosely as it deserves: one throttle was reached after roughly twenty to
 * thirty requests inside an hour, far below the documented daily maximum, so it was short-term limiting
 * rather than exhaustion. It survived two runs about ten to twenty seconds apart and had cleared by the
 * end of a two-minute wait. That brackets recovery somewhere between about twenty and about a hundred and
 * fifty seconds; the lower bound was never probed. Sixty seconds sits inside that bracket, which means the
 * first attempt after the window will sometimes reopen it. That is the gate working rather than a wrong
 * number: reopening costs one request, where the loop it replaces costs an unbounded number.
 *
 * Whether to try again, and when, is properly a decision for whatever issues challenges, because that is
 * the only party that can see the budget across every app sharing it. This is the local safety net under
 * that decision, not a replacement for it.
 */
private val DEFAULT_WINDOW = 60.seconds

/**
 * Refuses attestation for a window after the platform reports the shared request budget spent.
 *
 * Without this, [AttestationException.Throttled] is advice a caller is free to ignore: the exception
 * carries no state, so the next call goes straight back to the platform. A caller retrying in a loop then
 * spends requests against a budget that is already gone, and every device doing the same is what turns a
 * throttle into an outage. The gate makes the refusal structural instead of advisory.
 *
 * Monotonic rather than wall-clock, so the window survives sleep and clock changes, matching how token
 * expiry is counted elsewhere in this SDK.
 */
internal class ThrottleGate(
    private val window: Duration = DEFAULT_WINDOW,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var openUntil: TimeMark? = null

    /**
     * Throws [AttestationException.Throttled] while the window is open, without reaching the platform.
     *
     * Called before the challenge is spent, so a refused attempt does not consume a value the caller would
     * then have to replace. The reported code is the platform's own throttle code, because that is what the
     * condition is: this is the same failure the platform gave, held rather than re-asked.
     */
    suspend fun check() {
        val until = mutex.withLock { openUntil } ?: return
        if (until.hasNotPassedNow()) {
            throw AttestationException.Throttled(IntegrityErrorCode.TOO_MANY_REQUESTS)
        }
    }

    /** Opens the window. Called when the platform reports the budget spent. */
    suspend fun record() {
        mutex.withLock { openUntil = timeSource.markNow() + window }
    }
}
