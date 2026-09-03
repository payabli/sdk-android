package com.payabli.sdk.core.auth

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.isHeaderSafe
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.impl.RedactedCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

private const val REASON_NO_TOKEN_PROVIDER = "no tokenProvider was supplied"
private const val REASON_REFRESH_FAILED = "token refresh failed"
private const val REASON_PROVIDER_TIMEOUT = "the tokenProvider did not return in time"
private const val REASON_REFRESH_CANCELLED = "the refresh was cancelled"
private const val REASON_BLANK_TOKEN = "the tokenProvider returned a blank token"
private const val REASON_UNUSABLE_TOKEN = "the tokenProvider returned a token that cannot be a header value"
private const val REASON_UNCHANGED_TOKEN = "the tokenProvider returned the rejected token"

/**
 * The ceiling on one call to the host's token provider.
 *
 * Bounds a provider that never returns, so it cannot wedge every reader waiting on the same refresh.
 *
 * Only binds cancellation-cooperative code. A provider blocking a thread outside a suspension point
 * cannot be interrupted by any timeout, which is why the contract asks for cooperation.
 *
 * A hang detector, not a latency budget. Minting a token is one whole network round trip to the host's own
 * backend, so it belongs on the tier the transport bounds a whole exchange with, not on the tier that bounds
 * a single socket read. Anything reaching this ceiling is stuck rather than slow. Expiring early is the more
 * expensive mistake: a reader gets a token failure on a payment that would have gone through, where expiring
 * late only makes a caller wait longer for an error that was already coming.
 *
 * Not nested inside the transport's whole-call budget and never was: a refresh runs between two calls, and a
 * joining reader takes this deadline before any call budget starts. The two are independent ceilings on the
 * same shape of work, which is why they land on the same magnitude. What bounds a whole recovery is
 * `RetryPolicy.totalTimeoutMillis`, not this.
 *
 * Fixed, not configuration: nothing outside `:core` can set it. A host that could widen a hang detector could
 * ask every reader to wait longer on its own broker, and this is already generous enough that a broker which
 * is working does not reach it.
 */
internal const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 30_000L

/**
 * Holds the access token and refreshes it through the host's provider.
 *
 * `@RestrictTo` rather than public: a public token accessor would hand an app the credential the SDK
 * exists to hold on its behalf.
 *
 * Concurrent refreshes share one provider call and one outcome, so a rejected token cannot fan out into
 * one provider call per in-flight request.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PayabliAuth(
    private val config: PayabliConfig,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.AUTH),
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
) {
    init {
        // RetryPolicy validates its timings the same way. Zero or negative would time out before the
        // provider was ever called and report that as a token failure.
        require(providerTimeoutMillis > 0) { "providerTimeoutMillis must be positive" }
    }

    // Not private so a test can hold it and force the cleanup and commit paths to contend. Both run under
    // NonCancellable precisely for that case, and an uncontended lock cannot demonstrate it.
    @VisibleForTesting
    internal val mutex = Mutex()
    private var currentToken: String = config.accessToken
    private var inFlight: Refresh? = null

    // A stalled collector must not stall a refresh, so this buffers and drops rather than suspending
    // the emitter, which a rendezvous SharedFlow would do.
    private val tokenChangeSink =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Emits once per successful refresh. Carries the token, so it is internal like the rest of this type. */
    public val tokenChanges: SharedFlow<String> = tokenChangeSink.asSharedFlow()

    /**
     * Whether a rejected token can be replaced at all.
     *
     * False means no provider was supplied, so every refresh from now on fails the same way and the session
     * is beyond recovery from inside the SDK. `AuthenticatedTransport` reads this to tell that apart from a
     * provider that merely failed this once, which is transient and must not finish the session.
     *
     * `internal` rather than `@RestrictTo`: it is a fact about this holder that only `:core`'s own choke-point
     * acts on, and a capability that could read it would be reading how auth is configured.
     */
    internal val canRefresh: Boolean
        get() = config.tokenProvider != null

    /**
     * Why this instance is finished, or null while it still works.
     *
     * Written only under [mutex], beside the refresh claim, so no refresh can start after it is set.
     * `@Volatile` so a caller can read it without taking the lock on every request: a stale read can only
     * be permissive, never wrongly terminal, and the authoritative read is the one inside the claim.
     */
    @Volatile
    private var finished: PayabliException? = null

    /** Non-null once this instance is finished. See [finished]. */
    internal val terminalFailure: PayabliException?
        get() = finished

    /**
     * Marks this instance finished when [token] is still the credential it would send **and** no refresh is
     * in flight, and reports whether it did.
     *
     * Two conditions rather than one, because "still current" does not mean "nothing is about to replace
     * it". A claim is taken before the provider is called and [currentToken] is only written when the
     * refresh commits, so throughout a refresh the token being replaced is still the current one. A caller
     * that checked currency alone would finish on evidence about a credential already on its way out.
     *
     * Deciding and recording happen in one lock acquisition, so a refresh cannot begin between them. That
     * is what makes the guarantee in [invalidateAndRefresh] hold: nothing is set while a refresh runs, and
     * nothing runs after it is set.
     */
    internal suspend fun finishIfSettledOn(
        token: String,
        failure: PayabliException,
    ): Boolean =
        mutex.withLock {
            if (inFlight != null || currentToken != token) return@withLock false
            finished = failure
            true
        }

    /** Marks this instance finished outright, for a failure no rotation could have fixed. */
    internal suspend fun finish(failure: PayabliException) {
        mutex.withLock { finished = failure }
    }

    /** The token to send now. Never refreshes on its own; call [invalidateAndRefresh] after a rejection. */
    public suspend fun accessToken(): String {
        reentrantToken()?.let { return it }
        val read =
            mutex.withLock {
                val refresh = inFlight
                if (refresh != null) {
                    TokenRead.Refreshing(refresh.claim)
                } else {
                    TokenRead.Ready(currentToken)
                }
            }

        return when (read) {
            is TokenRead.Ready -> read.token
            is TokenRead.Refreshing -> read.refresh.await()
        }
    }

    /**
     * Reports [rejectedToken] as refused and returns the token to use instead.
     *
     * Passing the token that was actually rejected is what makes a staggered rejection cheap: two
     * requests sent with the same token can have their 401s arrive far apart, and the later one must not
     * refresh again on a token that has already rotated. That would discard the rotation the first one
     * obtained.
     *
     * So: finished refuses outright, already rotated returns the current token untouched, a refresh in
     * flight joins it and shares its outcome, and otherwise this caller runs the provider.
     *
     * **This block is the only place a claim is created, which is what makes the refusal complete.** Once
     * [finished] is set no caller can reach the provider, whatever stage it had already got to elsewhere,
     * so the host's broker is never called again after this instance is done.
     */
    public suspend fun invalidateAndRefresh(rejectedToken: String): String {
        reentrantToken()?.let { return it }
        val plan =
            mutex.withLock {
                val existing = inFlight
                val terminated = finished
                when {
                    // First, so nothing below can take a claim on an instance that is already finished.
                    terminated != null -> RefreshPlan.Refused(terminated)
                    // Before AlreadyRotated: the current token may itself be the one under refresh, and
                    // handing it back would return a credential already known to be rejected.
                    existing != null -> RefreshPlan.Join(existing.claim)
                    currentToken != rejectedToken -> RefreshPlan.AlreadyRotated(currentToken)
                    else -> RefreshPlan.Own(Refresh().also { inFlight = it })
                }
            }
        return when (plan) {
            is RefreshPlan.Refused -> throw plan.failure
            is RefreshPlan.AlreadyRotated -> plan.token
            is RefreshPlan.Join -> plan.claim.await()
            is RefreshPlan.Own -> runRefresh(plan.refresh, rejectedToken)
        }
    }

    /** Restores the token from [PayabliConfig], drops any in-flight claim, and revives a finished instance. */
    @VisibleForTesting
    internal suspend fun reset() {
        mutex.withLock {
            currentToken = config.accessToken
            inFlight = null
            // Without this a test that finishes the instance leaves it dead for every later one, and the
            // failure lands somewhere unrelated.
            finished = null
        }
    }

    private suspend fun runRefresh(
        refresh: Refresh,
        rejectedToken: String,
    ): String {
        val shared = refresh.claim
        val provider =
            config.tokenProvider
                ?: fail(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_NO_TOKEN_PROVIDER))

        // Appended rather than replaced: an element is keyed by its companion, so installing a fresh one
        // hides every enclosing refresh, and a call back into one of those joins the refresh awaiting it.
        val marks = currentCoroutineContext()[RefreshInProgress]?.marks.orEmpty() + Mark(this, refresh.id)

        val minted: String? =
            try {
                withTimeoutOrNull(providerTimeoutMillis.milliseconds) {
                    withContext(RefreshInProgress(marks)) { provider.freshToken() }
                }
            } catch (cancellation: CancellationException) {
                // A provider can raise cancellation of its own, from a nested timeout for instance, while
                // this coroutine is still active. That is a provider failure, not the caller withdrawing.
                if (currentCoroutineContext().isActive) {
                    fail(shared, providerFailure(cancellation))
                }
                // Waiters were not cancelled, so they get a token failure; a foreign CancellationException
                // would make their own scope look like it is unwinding.
                finish(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_REFRESH_CANCELLED))
                throw cancellation
            } catch (failure: Exception) {
                // Exception, not Throwable: an OutOfMemoryError or LinkageError is not a token failure.
                fail(shared, providerFailure(failure))
            } catch (fatal: Throwable) {
                // It still reaches the caller unchanged, but the claim cannot outlive it or every later
                // reader waits on a deferred nobody owns. No cause attached: it would pin whatever died.
                finish(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_REFRESH_FAILED))
                throw fatal
            }

        // Raised inside the try, this is caught below and re-wrapped, so the initiator would see a
        // different reason from the waiters.
        val fresh =
            minted ?: fail(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_PROVIDER_TIMEOUT))

        // PayabliConfig rejects a blank token at construction, so a refresh must not install one either.
        if (fresh.isBlank()) {
            fail(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_BLANK_TOKEN))
        }

        // A CR or LF here would be header injection, and the platform would throw an unchecked exception from
        // inside the transport rather than a PayabliException. Refused for the same reason blank is.
        if (!fresh.isHeaderSafe()) {
            fail(shared, PayabliGenericException(PayabliErrorCode.TOKEN_MALFORMED, REASON_UNUSABLE_TOKEN))
        }

        // The same credential the server just refused. Committing it would publish a rotation that did not
        // happen and hand the caller a token that is going to be rejected again, and because currentToken
        // would be unchanged, the next rejection starts another provider call instead of taking the
        // already-rotated shortcut: one provider call per 401, for as long as the provider keeps doing it.
        if (fresh == rejectedToken) {
            fail(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_UNCHANGED_TOKEN))
        }

        // Emitted under the same lock that commits and releases, so a second refresh cannot publish its
        // newer token first and leave collectors seeing rotations out of order.
        // NonCancellable for the same reason as the failure path: the token is already minted, and
        // cancellation arriving while this lock is contended would leave the claim set with no owner.
        withContext(NonCancellable) {
            mutex.withLock {
                currentToken = fresh
                tokenChangeSink.tryEmit(fresh)
                inFlight = null
            }
            shared.complete(fresh)
        }
        logger.info(LogField.safe("event", "token_refreshed")) { "access token refreshed" }
        return fresh
    }

    /** Anything the provider raised, redacted: it is host code, whatever type it chose to throw. */
    private fun providerFailure(failure: Throwable): PayabliGenericException =
        PayabliGenericException(
            PayabliErrorCode.TOKEN_EXPIRED,
            REASON_REFRESH_FAILED,
            cause = RedactedCause(failure),
        )

    /** Releases the claim and hands [outcome] to the waiters, then throws it. */
    private suspend fun fail(
        shared: CompletableDeferred<String>,
        outcome: PayabliGenericException,
    ): Nothing {
        finish(shared, outcome)
        logger.error(outcome, LogField.safe("errorCode", outcome.code)) { "token refresh failed" }
        throw outcome
    }

    /**
     * Cleanup runs under [NonCancellable] because liveness depends on it: a claim left set with nobody to
     * complete it wedges every later reader and refresh.
     *
     * Whether `withLock` observes an already-cancelled job depends on whether it has to suspend, since a
     * cancellable function only checks at a suspension point and the uncontended path does not suspend.
     * Correctness must not rest on which path it happens to take.
     */
    private suspend fun finish(
        shared: CompletableDeferred<String>,
        outcome: PayabliGenericException,
    ) = withContext(NonCancellable) {
        mutex.withLock { inFlight = null }
        shared.completeExceptionally(outcome)
    }

    /**
     * Non-null only when this coroutine is inside this instance's provider call for the refresh it still
     * has in flight.
     *
     * The refresh is named as well as the holder: a mark reaches a coroutine that outlived the refresh
     * that set it only through a scope built from the calling context, and answering on such a mark would
     * hand a caller the very token it had just reported rejected.
     */
    private suspend fun reentrantToken(): String? {
        val marks = currentCoroutineContext()[RefreshInProgress]?.marks ?: return null
        return mutex.withLock {
            val live = inFlight
            if (live != null && marks.any { it.heldBy(this@PayabliAuth) && it.id == live.id }) {
                currentToken
            } else {
                null
            }
        }
    }

    /**
     * Every refresh whose provider call this coroutine is inside, outermost first, so a re-entrant read or
     * refresh returns the last known token instead of awaiting the claim its own caller has to complete.
     *
     * A chain rather than one entry. A provider may call a second session whose provider calls back into
     * the first, and an entry that replaced its predecessor leaves that first session unmarked, so the
     * call back into it joins the refresh that is waiting on it.
     *
     * Follows `withContext` and child coroutines; a provider that hops to an unrelated scope escapes it,
     * and is then bounded by [providerTimeoutMillis] only as far as that deadline reaches, which is
     * cooperative code.
     */
    private class RefreshInProgress(
        val marks: List<Mark>,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RefreshInProgress>
    }

    /**
     * One entry in [RefreshInProgress]: which holder, and which of its refreshes.
     *
     * The holder is held weakly. A mark can reach a coroutine that outlives the refresh that set it, and a
     * strong reference there would keep the session, and the token it holds, alive after the host had
     * released both. Nothing is lost by it: while a refresh runs its own holder is on the calling stack,
     * so a live refresh can never see its holder cleared, and a cleared one matches nothing.
     */
    private class Mark(
        holder: PayabliAuth,
        val id: UUID,
    ) {
        private val holderRef = WeakReference(holder)

        fun heldBy(candidate: PayabliAuth): Boolean = holderRef.get() === candidate
    }

    /**
     * One refresh: the claim its waiters await, and the identity a mark names it by.
     *
     * One value rather than two fields, so releasing the claim cannot leave the identity behind.
     */
    private class Refresh {
        val claim = CompletableDeferred<String>()
        val id: UUID = UUID.randomUUID()
    }

    private sealed interface RefreshPlan {
        data class Refused(
            val failure: PayabliException,
        ) : RefreshPlan

        data class AlreadyRotated(
            val token: String,
        ) : RefreshPlan

        data class Join(
            val claim: CompletableDeferred<String>,
        ) : RefreshPlan

        data class Own(
            val refresh: Refresh,
        ) : RefreshPlan
    }

    private sealed interface TokenRead {
        data class Ready(
            val token: String,
        ) : TokenRead

        data class Refreshing(
            val refresh: CompletableDeferred<String>,
        ) : TokenRead
    }
}
