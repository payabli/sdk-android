package com.payabli.sdk.taptopay.attestation.impl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A platform error, carried across the seam as a plain integer.
 *
 * The integer is the whole point. Play Integrity's error constants live on two Android types, and its
 * exceptions are Play services types; anything above this line that touched either would be a file no
 * unit test could reach. So the gateway reduces a platform failure to its code here, and every decision
 * about what that code *means* is taken on this side, on the JVM, under test.
 *
 * The [cause] is kept for a stack trace and is never inspected.
 *
 * [errorCode] is null where the platform failed without one: a gateway converts every **exception** it
 * sees, not only the two integrity types, because a caller must never receive a raw platform exception
 * from a Play services internal it cannot classify. A coded failure and an uncoded one are both failures;
 * only the coded one can be classified precisely.
 *
 * `Exception`, deliberately, not `Throwable`. A JVM `Error` propagates untouched: an `OutOfMemoryError` is
 * not an integrity failure and reporting it as one would hide it behind a retry. Do not broaden the catch
 * to match this sentence. `CancellationException` is re-thrown for its own reason, being a caller
 * withdrawing rather than a device failing.
 */
internal class IntegrityFailure(
    val errorCode: Int?,
    cause: Throwable? = null,
) : Exception("integrity error ${errorCode ?: "unreported"}", cause)

/**
 * The ceiling on a single platform call.
 *
 * Nothing in the Play Integrity API promises to return. A wedged binder or an unresponsive Play services
 * leaves `Task.await()` suspended for as long as the caller's scope lives, and for a card reader that means
 * arming hangs with a merchant waiting. The transport already bounds a whole call for the same reason.
 *
 * Thirty seconds is a hang detector, not a performance target: the platform documents a standard request in
 * a few hundred milliseconds and a classic one in a few seconds, so anything reaching this is not slow, it
 * is stuck. Generous on purpose, because expiring a call that would have succeeded costs a request against
 * a shared budget.
 */
internal val DEFAULT_PLATFORM_DEADLINE: Duration = 30.seconds

/**
 * Runs [block] under [deadline], reporting expiry as an uncoded [IntegrityFailure].
 *
 * `withTimeoutOrNull` rather than `withTimeout`, deliberately. `withTimeout` signals expiry with a
 * `CancellationException`, which the gateways forward untouched because a caller withdrawing is not a
 * device failure; an SDK-owned deadline would then reach the caller as cancellation rather than as an
 * attestation outcome. Returning null keeps the two apart, and an outer cancellation still propagates.
 *
 * Uncoded because the platform said nothing, which maps to the retryable disposition: a stuck call is worth
 * trying again, unlike a spent budget.
 */
internal suspend fun <T> underDeadline(
    deadline: Duration,
    block: suspend () -> T,
): T {
    val outcome = withTimeoutOrNull(deadline) { Result.success(block()) }
    if (outcome == null) {
        // Cancellation can land between the null and this throw, with nothing suspending in between, and a
        // withdrawn caller must not be told the platform timed out. The transport and the retry primitive
        // both guard the same window the same way.
        currentCoroutineContext().ensureActive()
        throw IntegrityFailure(null)
    }
    return outcome.getOrThrow()
}

/**
 * The platform half of a standard request, which is two steps rather than one.
 *
 * Preparing a provider is a network round trip and its result is reusable; requesting a token against a
 * prepared provider is the cheap part. Splitting them is not an optimisation, it is the shape the platform
 * has: a provider expires independently of any request made through it.
 */
internal fun interface StandardIntegrityGateway {
    /** Prepares a provider for [cloudProjectNumber]. Throws [IntegrityFailure] for a platform error. */
    suspend fun prepareProvider(cloudProjectNumber: Long): StandardTokenRequester
}

/** A prepared provider. Reusable until the platform decides it is not. */
internal fun interface StandardTokenRequester {
    /** Requests a token bound to [requestHash]. Throws [IntegrityFailure] for a platform error. */
    suspend fun request(requestHash: String): String
}

/**
 * The platform half of a classic request, which is one step and keeps nothing.
 *
 * [cloudProjectNumber] is nullable because the platform makes it optional here: an app distributed through
 * Play has one already, by way of its Play Console linkage, and supplying it is only required where that
 * linkage does not exist. A standard request has no such fallback, which is why its gateway demands one.
 */
internal fun interface ClassicIntegrityGateway {
    /** Requests a token bound to [nonce]. Throws [IntegrityFailure] for a platform error. */
    suspend fun requestToken(
        nonce: String,
        cloudProjectNumber: Long?,
    ): String
}
