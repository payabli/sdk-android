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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val REASON_NO_TOKEN_PROVIDER = "no tokenProvider was supplied"
private const val REASON_REFRESH_FAILED = "token refresh failed"

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
    public suspend fun currentAccessToken(): String = mutex.withLock { currentToken }

    /**
     * Marks the current token rejected and returns a fresh one.
     *
     * The first caller runs the provider; callers arriving while it is in flight await that same result
     * and receive the same outcome, including the same failure.
     */
    public suspend fun invalidateAndRefresh(): String {
        val (shared, isInitiator) =
            mutex.withLock {
                val existing = inFlight
                if (existing != null) {
                    existing to false
                } else {
                    val claim = CompletableDeferred<String>()
                    inFlight = claim
                    claim to true
                }
            }
        return if (isInitiator) runRefresh(shared) else shared.await()
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
            val fresh = provider.freshToken()
            mutex.withLock {
                currentToken = fresh
                inFlight = null
            }
            shared.complete(fresh)
            tokenChangeSink.tryEmit(fresh)
            logger.info(LogField.safe("event", "token_refreshed")) { "access token refreshed" }
            return fresh
        } catch (cancellation: CancellationException) {
            // Hand the cancellation to the waiters rather than leaving them awaiting a claim nobody owns.
            release()
            shared.completeExceptionally(cancellation)
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
        failure as? PayabliGenericException
            ?: PayabliGenericException(
                PayabliErrorCode.TOKEN_EXPIRED,
                REASON_REFRESH_FAILED,
                cause = RedactedCause(failure),
            )
}
