package com.payabli.sdk.core.auth

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.impl.RedactedCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/** A host provider that never returns must not wedge every reader, so its call is bounded. */
private const val DEFAULT_PROVIDER_TIMEOUT_MILLIS = 30_000L

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
    private val logger: PayabliLogger = PayabliLoggers.of(LogCategory.AUTH),
    private val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()
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
                    currentToken != rejectedToken -> RefreshPlan.AlreadyRotated(currentToken)
                    existing != null -> RefreshPlan.Join(existing)
                    else -> RefreshPlan.Own(CompletableDeferred<String>().also { inFlight = it })
                }
            }
        return when (plan) {
            is RefreshPlan.AlreadyRotated -> plan.token
            is RefreshPlan.Join -> plan.claim.await()
            is RefreshPlan.Own -> runRefresh(plan.claim)
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

    private suspend fun runRefresh(shared: CompletableDeferred<String>): String {
        try {
            val provider =
                config.tokenProvider
                    ?: throw PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_NO_TOKEN_PROVIDER)
            val fresh =
                withTimeoutOrNull(providerTimeoutMillis.milliseconds) {
                    withContext(RefreshInProgress(this@PayabliAuth)) { provider.freshToken() }
                } ?: throw PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_PROVIDER_TIMEOUT)
            mutex.withLock {
                currentToken = fresh
                inFlight = null
            }
            tokenChangeSink.tryEmit(fresh)
            shared.complete(fresh)
            logger.info(LogField.safe("event", "token_refreshed")) { "access token refreshed" }
            return fresh
        } catch (cancellation: CancellationException) {
            // Waiters were not cancelled, so they get a token failure; a foreign CancellationException
            // would make their own scope look like it is unwinding.
            release()
            shared.completeExceptionally(
                PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_REFRESH_CANCELLED),
            )
            throw cancellation
        } catch (failure: Throwable) {
            release()
            val mapped = mapFailure(failure)
            shared.completeExceptionally(mapped)
            logger.error(mapped, LogField.safe("errorCode", mapped.code)) { "token refresh failed" }
            throw mapped
        }
    }

    private suspend fun release() = mutex.withLock { inFlight = null }

    /**
     * The provider is host code, so its message can carry anything its backend returned. The cause is
     * redacted for the same reason a decode failure's is.
     */
    private fun mapFailure(failure: Throwable): PayabliGenericException =
        (failure as? PayabliGenericException)?.takeIf { it.code == PayabliErrorCode.TOKEN_EXPIRED }
            ?: PayabliGenericException(
                PayabliErrorCode.TOKEN_EXPIRED,
                REASON_REFRESH_FAILED,
                cause = RedactedCause(failure),
            )

    /** Non-null only when this coroutine is already inside this instance's provider call. */
    private suspend fun reentrantToken(): String? =
        if (currentCoroutineContext()[RefreshInProgress]?.auth === this) mutex.withLock { currentToken } else null

    /**
     * Marks the provider call so a re-entrant read or refresh returns the last known token instead of
     * awaiting the claim its own caller has to complete. Follows `withContext` and child coroutines; a
     * provider that hops to an unrelated scope escapes it and is bounded by [providerTimeoutMillis].
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
