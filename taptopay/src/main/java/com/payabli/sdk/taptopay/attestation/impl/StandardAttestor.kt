package com.payabli.sdk.taptopay.attestation.impl

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.taptopay.attestation.AppAttestor
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.AttestationToken
import com.payabli.sdk.taptopay.attestation.VerdictClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * Attests through a standard request: a provider prepared once, then a token per challenge.
 *
 * Free of Android types. Everything platform-shaped is behind [StandardIntegrityGateway], which is what
 * lets the provider lifecycle below, the part with the actual behaviour in it, be exercised on the JVM.
 *
 * [cloudProjectNumber] is a `Long` rather than a nullable one because a standard request cannot be made
 * without it. Making it required here means there is no run-time state where this object exists and cannot
 * work, and no error case to write for one.
 */
internal class StandardAttestor(
    private val gateway: StandardIntegrityGateway,
    private val cloudProjectNumber: Long,
    private val ledger: ChallengeLedger = ChallengeLedger(),
    private val throttleGate: ThrottleGate = ThrottleGate(),
    private val deadline: Duration = DEFAULT_PLATFORM_DEADLINE,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) : AppAttestor {
    private val mutex = Mutex()

    /**
     * The prepared provider, or null before the first preparation and after one is discarded.
     *
     * Read outside the lock on the fast path, which is safe in one direction only: a stale null costs an
     * extra lock acquisition and a re-check, while a stale non-null cannot happen, because the field is
     * only ever written under the lock and only ever read as a whole reference.
     */
    @Volatile
    private var prepared: StandardTokenRequester? = null

    /**
     * The preparation currently running, or null when none is.
     *
     * Written only under [mutex]. Callers arriving while it is set wait on it rather than starting a second
     * preparation, and it is cleared as the outcome is published, so the cohort that joined shares one
     * result and the next caller is free to try again.
     */
    private var inFlight: CompletableDeferred<StandardTokenRequester>? = null

    override suspend fun warmUp() {
        // The gate guards this path as well. Preparing is itself a platform request that can be refused
        // for a spent budget, and report() opens the gate without caching anything, so a warm-up loop
        // would otherwise walk straight back to an exhausted budget on every call.
        throttleGate.check()
        requester()
    }

    override suspend fun attest(challenge: AttestationChallenge): AttestationToken {
        require(challenge.verdictClass == VerdictClass.STANDARD) {
            "this attestor makes standard requests; the challenge was built for ${challenge.verdictClass}"
        }
        // Before the challenge is spent, so a refused attempt does not burn a value the caller must replace.
        throttleGate.check()

        // Prepared first, then spent. Preparing is a network round trip that can fail or be cancelled
        // without any request carrying the challenge, and spending ahead of it would refuse the caller's
        // perfectly reasonable retry with the same value as ChallengeReused.
        val used = requester()
        // Now, immediately before the request. A challenge is spent by being offered to the platform.
        try {
            ledger.spend(challenge.value)
        } catch (reused: AttestationException.ChallengeReused) {
            // The only failure here that is unambiguously the caller's mistake, and the only one that left
            // no trace: it is raised before the platform is consulted, so none of the paths below run.
            logger.error(
                LogField.safe("event", "attestation_challenge_reused"),
                LogField.safe("verdictClass", VerdictClass.STANDARD.name),
            ) { "the standard challenge had already been offered" }
            throw reused
        }
        val token =
            try {
                underDeadline(deadline) { used.request(challenge.value) }
            } catch (failure: IntegrityFailure) {
                if (failure.errorCode != StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                    throw report(failure)
                }
                // The provider expired or Play Store data was cleared. Discard it, prepare a fresh one and
                // make the request once more. Exactly once: a second invalid answer is a condition a third
                // attempt will not change, and looping here would hide it behind latency instead.
                discard(used)
                val replacement = requester()
                try {
                    underDeadline(deadline) { replacement.request(challenge.value) }
                } catch (retried: IntegrityFailure) {
                    // Discard the replacement too when it is invalid in its turn, or it stays cached and the
                    // next attestation spends a platform request discovering what this one already knows.
                    if (retried.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                        discard(replacement)
                    }
                    throw report(retried)
                }
            }
        return AttestationToken(token)
    }

    /**
     * The prepared provider, preparing one if there is none.
     *
     * Concurrent callers collapse onto a single preparation, and **the lock is held only to decide whether
     * this caller owns that preparation or joins one, never across the platform call itself.** The claim is
     * what the others wait on, so one preparation serves the whole burst whether it succeeds or fails.
     *
     * Holding the lock across the call instead would look equivalent and is not: on success it is, because
     * the waiters wake to a set field, but on failure every waiter in turn finds the field still null and
     * starts its own preparation. A burst of N against a stalled Play services would then serialise into N
     * platform deadlines and N requests against the shared budget, which is the outcome the rest of this
     * class exists to avoid. Sharing the failure costs the cohort one attempt and leaves retrying to
     * whoever arrives next.
     *
     * The token refresh path in the core module resolves the same problem the same way, and the reasoning
     * for the cancellation and cleanup rules below is written out there in full.
     */
    private suspend fun requester(): StandardTokenRequester {
        prepared?.let { return it }
        val plan =
            mutex.withLock {
                val ready = prepared
                val joined = inFlight
                when {
                    ready != null -> PreparePlan.Ready(ready)
                    joined != null -> PreparePlan.Join(joined)
                    else -> {
                        // Re-checked inside the lock, because the gate can close while this caller queues
                        // on it: the preparation it is queued behind can be the one that reports the budget
                        // spent. Checking on entry costs nothing when the gate is open.
                        throttleGate.check()
                        PreparePlan.Own(CompletableDeferred<StandardTokenRequester>().also { inFlight = it })
                    }
                }
            }
        return when (plan) {
            is PreparePlan.Ready -> plan.requester
            is PreparePlan.Join -> plan.claim.await()
            is PreparePlan.Own -> prepare(plan.claim)
        }
    }

    /** Prepares a provider and hands the outcome, success or failure, to everyone holding [shared]. */
    private suspend fun prepare(shared: CompletableDeferred<StandardTokenRequester>): StandardTokenRequester {
        val fresh =
            try {
                underDeadline(deadline) { gateway.prepareProvider(cloudProjectNumber) }
            } catch (failure: IntegrityFailure) {
                // report() maps, logs, and closes the gate when the budget is spent, all before the waiters
                // are released, so a joiner cannot wake and walk back into a platform it was just refused by.
                val mapped = report(failure)
                release(shared, mapped)
                throw mapped
            } catch (cancellation: CancellationException) {
                // This caller withdrew. The waiters did not, so they get a retryable failure instead: a
                // foreign CancellationException would make their own scopes look like they are unwinding.
                release(shared, AttestationException.Retryable(null))
                throw cancellation
            } catch (unexpected: Exception) {
                // Exception, not Throwable: an OutOfMemoryError is not an attestation failure. It still
                // reaches this caller unchanged, but the claim cannot outlive it or every later caller
                // waits on a deferred nobody owns.
                release(shared, AttestationException.Retryable(null, unexpected))
                throw unexpected
            }
        // NonCancellable for the reason the cleanup path is: the provider is already prepared, and
        // cancellation arriving while this lock is contended would leave the claim set with no owner.
        withContext(NonCancellable) {
            mutex.withLock {
                prepared = fresh
                inFlight = null
            }
            shared.complete(fresh)
        }
        return fresh
    }

    /**
     * Drops the claim and hands [outcome] to the waiters.
     *
     * It does not throw, because what the *owner* raises is not always what the waiters are told: a caller
     * that withdrew re-throws its own cancellation while the waiters get something retryable.
     *
     * Under [NonCancellable] because liveness depends on it: a claim left set with nobody to complete it
     * wedges every later caller. Whether `withLock` observes an already-cancelled job depends on whether it
     * has to suspend, and correctness must not rest on which path it happens to take.
     */
    private suspend fun release(
        shared: CompletableDeferred<StandardTokenRequester>,
        outcome: Exception,
    ) = withContext(NonCancellable) {
        mutex.withLock { inFlight = null }
        shared.completeExceptionally(outcome)
        Unit
    }

    /**
     * Drops [stale], unless it has already been replaced by something else.
     *
     * The identity check is against the provider the **caller was using**, which is why it is a parameter
     * rather than a re-read of the field. Two attestations can both be told the same provider is invalid;
     * without this the second would discard the replacement the first had just prepared, and one expiry
     * would become an unbounded run of preparations under any concurrent load.
     */
    private suspend fun discard(stale: StandardTokenRequester) {
        mutex.withLock {
            if (prepared === stale) prepared = null
        }
    }

    private suspend fun report(failure: IntegrityFailure): Exception {
        val mapped = PlayIntegrityErrorMapping.failureFor(failure.errorCode, VerdictClass.STANDARD, failure)
        logger.error(
            LogField.safe("event", "attestation_failed"),
            LogField.safe("verdictClass", VerdictClass.STANDARD.name),
            // As a string, so a failure the platform reported without a code records as null rather than
            // as some stand-in integer a reader would take for a real code.
            LogField.safe("errorCode", failure.errorCode?.toString()),
        ) { "standard integrity request failed" }
        if (mapped is AttestationException.Throttled) throttleGate.record()
        return mapped
    }

    /** What a caller found when it looked for a provider, decided under [mutex] and acted on outside it. */
    private sealed interface PreparePlan {
        /** One was already prepared. */
        class Ready(
            val requester: StandardTokenRequester,
        ) : PreparePlan

        /** Someone else is preparing; wait for their outcome rather than starting a second. */
        class Join(
            val claim: CompletableDeferred<StandardTokenRequester>,
        ) : PreparePlan

        /** Nobody is; this caller prepares and publishes the result to anyone who joins. */
        class Own(
            val claim: CompletableDeferred<StandardTokenRequester>,
        ) : PreparePlan
    }
}
