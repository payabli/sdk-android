package com.payabli.sdk.taptopay.attestation.platform

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.payabli.sdk.taptopay.attestation.impl.ClassicIntegrityGateway
import com.payabli.sdk.taptopay.attestation.impl.IntegrityFailure
import com.payabli.sdk.taptopay.attestation.impl.StandardIntegrityGateway
import com.payabli.sdk.taptopay.attestation.impl.StandardTokenRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Runs [block], converting any platform failure into an [IntegrityFailure].
 *
 * The two integrity exception types carry a code; anything else the Play services stack raises does not,
 * and both become the same thing here. This is the boundary that guarantees no platform exception reaches
 * a caller, which is what lets everything above it be written against one failure type.
 *
 * Cancellation is not a failure and is re-thrown untouched. `await()` resumes with `CancellationException`
 * when the coroutine is cancelled, and swallowing that into an integrity error would report a caller's own
 * withdrawal as a device problem.
 */
private suspend fun <T> mappingFailures(block: suspend () -> T): T =
    try {
        block()
    } catch (failure: StandardIntegrityException) {
        throw IntegrityFailure(failure.errorCode, failure)
    } catch (failure: IntegrityServiceException) {
        throw IntegrityFailure(failure.errorCode, failure)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Exception) {
        // Exception, not Throwable: an OutOfMemoryError is not an integrity error and must not be
        // reported as one, following the same line the token refresh path draws.
        throw IntegrityFailure(null, unexpected)
    }

/**
 * The standard request against the real Play Integrity API.
 *
 * Holds a `Context`, so this is the tier only a device can exercise; it is also close to all of the Android
 * surface this capability has. What it does is bridge two `Task` calls into `suspend` and reduce a failure
 * to its code. Everything else lives on the other side of the seam, where a unit test can reach it.
 *
 * The application context, never an Activity or a Fragment: this outlives any of them and holding one
 * would leak it.
 */
internal class PlayStandardIntegrityGateway(
    context: Context,
) : StandardIntegrityGateway {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)

    override suspend fun prepareProvider(cloudProjectNumber: Long): StandardTokenRequester {
        val provider =
            mappingFailures {
                manager
                    .prepareIntegrityToken(
                        PrepareIntegrityTokenRequest
                            .builder()
                            .setCloudProjectNumber(cloudProjectNumber)
                            .build(),
                    ).await()
            }
        return StandardTokenRequester { requestHash ->
            mappingFailures {
                provider
                    .request(
                        StandardIntegrityTokenRequest
                            .builder()
                            .setRequestHash(requestHash)
                            .build(),
                    ).await()
                    .token()
            }
        }
    }
}

/** The classic request against the real Play Integrity API. See [PlayStandardIntegrityGateway]. */
internal class PlayClassicIntegrityGateway(
    context: Context,
) : ClassicIntegrityGateway {
    private val manager = IntegrityManagerFactory.create(context.applicationContext)

    override suspend fun requestToken(
        nonce: String,
        cloudProjectNumber: Long?,
    ): String =
        mappingFailures {
            val request =
                IntegrityTokenRequest
                    .builder()
                    .setNonce(nonce)
                    .apply { cloudProjectNumber?.let { setCloudProjectNumber(it) } }
                    .build()
            manager.requestIntegrityToken(request).await().token()
        }
}
