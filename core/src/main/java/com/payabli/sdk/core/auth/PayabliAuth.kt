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
 * Bounds a provider that never returns, so it cannot wedge every reader.
 *
 * Only binds cancellation-cooperative code. A provider blocking a thread outside a suspension point
 * cannot be interrupted by any timeout, which is why the contract asks for cooperation.
 *
 * Ten seconds rather than thirty: a provider that never returns holds every reader waiting on the same
 * refresh, and half a minute of that is most of a user's patience.
 *
 * Deliberately shorter than the transport's own whole-call budget, even though a broker callback also makes
 * a network round trip. `TransportFactory.authenticated` takes it as a parameter so `:core`'s tests can vary
 * it, and nothing outside `:core` can set it: a host with a legitimately slower broker has no way to widen
 * this today.
 */
internal const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 10_000L

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
    private var inFlight: CompletableDeferred<String>? = null

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
     * provider that merely failed this once, which is transient and must not condemn the session.
     *
     * `internal` rather than `@RestrictTo`: it is a fact about this holder that only `:core`'s own choke-point
     * acts on, and a capability that could read it would be reading how auth is configured.
     */
    internal val canRefresh: Boolean
        get() = config.tokenProvider != null

    /**
     * Runs [onSettled] under this holder's lock when [token] is still the credential it would send **and**
     * no refresh is in flight, and reports whether it ran.
     *
     * Two conditions rather than one, because "still current" does not mean "nothing is about to replace
     * it". A claim is taken before the provider is called and [currentToken] is only written when the
     * refresh commits, so throughout a refresh the token being replaced is still the current one. A caller
     * that checked currency alone would draw a conclusion about a credential already on its way out.
     *
     * [onSettled] runs under the lock so that a refresh cannot begin between the decision and whatever it
     * records, which is the same reason the commit above holds the lock across all three of its writes. It
     * must not suspend and must not re-enter this holder.
     */
    internal suspend fun finishIfSettledOn(
        token: String,
        onSettled: () -> Unit,
    ): Boolean =
        mutex.withLock {
            if (inFlight != null || currentToken != token) return@withLock false
            onSettled()
            true
        }

    /** The token to send now. Never refreshes on its own; call [invalidateAndRefresh] after a rejection. */
    public suspend fun accessToken(): String {
        reentrantToken()?.let { return it }
        val read =
            mutex.withLock {
                val refresh = inFlight
                if (refresh != null) {
                    TokenRead.Refreshing(refresh)
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
     * So: already rotated returns the current token untouched, a refresh in flight joins it and shares
     * its outcome, and otherwise this caller runs the provider.
     */
    public suspend fun invalidateAndRefresh(rejectedToken: String): String {
        reentrantToken()?.let { return it }
        val plan =
            mutex.withLock {
                val existing = inFlight
                when {
                    // Before AlreadyRotated: the current token may itself be the one under refresh, and
                    // handing it back would return a credential already known to be rejected.
                    existing != null -> RefreshPlan.Join(existing)
                    currentToken != rejectedToken -> RefreshPlan.AlreadyRotated(currentToken)
                    else -> RefreshPlan.Own(CompletableDeferred<String>().also { inFlight = it })
                }
            }
        return when (plan) {
            is RefreshPlan.AlreadyRotated -> plan.token
            is RefreshPlan.Join -> plan.claim.await()
            is RefreshPlan.Own -> runRefresh(plan.claim, rejectedToken)
        }
    }

    /** Restores the token from [PayabliConfig] and drops any in-flight claim. */
    @VisibleForTesting
    internal suspend fun reset() {
        mutex.withLock {
            currentToken = config.accessToken
            inFlight = null
        }
    }

    private suspend fun runRefresh(
        shared: CompletableDeferred<String>,
        rejectedToken: String,
    ): String {
        val provider =
            config.tokenProvider
                ?: fail(shared, PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_NO_TOKEN_PROVIDER))

        val minted: String? =
            try {
                withTimeoutOrNull(providerTimeoutMillis.milliseconds) {
                    withContext(RefreshInProgress(this@PayabliAuth)) { provider.freshToken() }
                }
            } catch (cancellation: CancellationException) {
                // A provider can raise cancellation of its own, from a nested timeout for instance, while
                // this coroutine is still active. That is a provider failure, not our caller withdrawing.
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

        // Outside the try on purpose: raised inside it, this was caught below and re-wrapped, so the
        // initiator saw a different reason from the waiters.
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

    /** Non-null only when this coroutine is already inside this instance's provider call. */
    private suspend fun reentrantToken(): String? =
        if (currentCoroutineContext()[RefreshInProgress]?.auth === this) mutex.withLock { currentToken } else null

    /**
     * Marks the provider call so a re-entrant read or refresh returns the last known token instead of
     * awaiting the claim its own caller has to complete. Follows `withContext` and child coroutines; a
     * provider that hops to an unrelated scope escapes it, and is then bounded by
     * [providerTimeoutMillis] only as far as that deadline reaches, which is cooperative code.
     */
    private class RefreshInProgress(
        val auth: PayabliAuth,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RefreshInProgress>
    }

    private sealed interface RefreshPlan {
        data class AlreadyRotated(
            val token: String,
        ) : RefreshPlan

        data class Join(
            val claim: CompletableDeferred<String>,
        ) : RefreshPlan

        data class Own(
            val claim: CompletableDeferred<String>,
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
