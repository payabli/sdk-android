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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
                used.request(challenge.value)
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
                    replacement.request(challenge.value)
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
     * Concurrent callers collapse onto a single preparation: the first takes the lock and prepares, the
     * rest wait and then find the field already set. Worth the lock rather than letting each caller prepare
     * its own, because preparation is a network round trip and the results are interchangeable, so N
     * concurrent attestations would otherwise cost N of them.
     */
    private suspend fun requester(): StandardTokenRequester {
        prepared?.let { return it }
        return mutex.withLock {
            prepared ?: run {
                // Re-checked inside the lock, because the gate can close while this caller queues on it.
                // A burst all passes the outer check while the field is null, the first one prepares and is
                // told the budget is spent, and every waiter behind it would then walk into this branch and
                // spend another known-doomed request. Checking on entry costs nothing when the gate is open.
                throttleGate.check()
                prepare().also { prepared = it }
            }
        }
    }

    private suspend fun prepare(): StandardTokenRequester =
        try {
            gateway.prepareProvider(cloudProjectNumber)
        } catch (failure: IntegrityFailure) {
            throw report(failure)
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
}
