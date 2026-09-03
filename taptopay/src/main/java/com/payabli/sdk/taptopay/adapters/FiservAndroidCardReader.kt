package com.payabli.sdk.taptopay.adapters

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.telemetry.TapToPayReports
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The ceiling on bringing the reader up. A hang detector: a first configuration on a handset takes up to
 * about two minutes, so anything past this is stuck.
 *
 * The tap carries no equivalent bound. It waits for a person.
 */
internal val DEFAULT_ARMING_DEADLINE: Duration = 180.seconds

/**
 * The shipping [TapToPayProvider], over the vendor's card reader.
 *
 * The credentials live from [configure] to [prepareReader] and no longer; a session that needs them again
 * fetches them again.
 */
internal class FiservAndroidCardReader(
    private val gateway: CardReaderGateway,
    private val eligibility: ReaderEligibility,
    private val armingDeadline: Duration = DEFAULT_ARMING_DEADLINE,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) : TapToPayProvider {
    /** Guards [pending]. Never held across a tap, which waits on a person. */
    private val setup = Mutex()

    private var pending: ReaderArming? = null

    override suspend fun checkEligibility() = eligibility.check()

    override suspend fun configure(credentials: ReaderCredentials) {
        val arming = credentials.toArming()
        setup.withLock { pending = arming }
    }

    override suspend fun prepareReader() {
        setup.withLock {
            val arming =
                pending ?: error("prepareReader was called with no credentials; configure comes first")
            // Dropped before the attempt. The local keeps them for this arming, so every way out of the
            // call below leaves nothing holding the vendor's key and secret.
            pending = null
            armWithin(arming)?.let { failure ->
                record("arm", failure)
                throw if (failure.kind in DENIALS) {
                    CardReaderException.DeviceDenied(failure)
                } else {
                    CardReaderException.ArmingFailed(failure)
                }
            }
            logger.debug(
                LogField.safe("event", "ttp_reader_armed"),
                LogField.safe("phase", "arm"),
            ) { "the reader came up" }
        }
    }

    override suspend fun startReading(request: CardReadRequest): CardReadResult {
        // The radio is what this measures, so the bracket is the tap and not the arming that preceded it.
        // A reader that never came up is reported by the phase that could not bring it up.
        val startedAt = System.nanoTime()
        TapToPayReports.nfcStarted()
        val record =
            try {
                gateway.startReading(
                    ReaderCharge(
                        amount = request.amount,
                        merchantTransactionId = request.merchantTransactionId,
                        merchantOrderId = request.merchantOrderId,
                    ),
                )
            } catch (failure: CardReaderFailure) {
                record("charge", failure)
                TapToPayReports.nfcFailed(failure, startedAt)
                throw failure.asChargeFailure()
            }
        TapToPayReports.nfcSucceeded(startedAt)
        return CardReadResult(cardNetwork = record.cardNetwork, providerResponse = record.encoded())
    }

    /**
     * Arms the reader under [armingDeadline], answering with the failure or null.
     *
     * `withTimeoutOrNull`, so expiry reaches the caller as a reader that did not come up. `withTimeout`
     * signals it with a `CancellationException`, which unwinds their scope.
     */
    private suspend fun armWithin(arming: ReaderArming): CardReaderFailure? {
        val outcome =
            withTimeoutOrNull(armingDeadline) {
                try {
                    gateway.prepareReader(arming)
                    Armed(null)
                } catch (failure: CardReaderFailure) {
                    Armed(failure)
                }
            }
        if (outcome == null) {
            // A cancellation can land between the null and this return with nothing suspending in between,
            // and a withdrawn caller must not be told the reader timed out.
            currentCoroutineContext().ensureActive()
            return CardReaderFailure(ReaderFailureKind.TIMED_OUT)
        }
        return outcome.failure
    }

    /** Separates a reader that came up from one that did not. */
    private class Armed(
        val failure: CardReaderFailure?,
    )

    private fun CardReaderFailure.asChargeFailure(): CardReaderException =
        when (kind) {
            ReaderFailureKind.SESSION_UNUSABLE -> CardReaderException.SessionUnusable(this)
            ReaderFailureKind.CONTACTLESS_UNAVAILABLE -> CardReaderException.ReadFailed(this)
            ReaderFailureKind.TIMED_OUT -> CardReaderException.ReadFailed(this)
            ReaderFailureKind.UNCLASSIFIED -> CardReaderException.ReadFailed(this)
            // A denial is terminal: the vendor refuses the handset, not the call. The session expires
            // rather than landing on DEVICE_INELIGIBLE, which Ready cannot reach; the repair lands it.
            ReaderFailureKind.DEVICE_DENIED -> CardReaderException.DeviceDenied(this)
            ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED -> CardReaderException.DeviceDenied(this)
        }

    private companion object {
        /** Both refuse the handset; they differ in whether the vendor has said why. */
        val DENIALS =
            setOf(ReaderFailureKind.DEVICE_DENIED, ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED)
    }

    /** The vendor's classification and its code. Its message is free text and is never a log field. */
    private fun record(
        phase: String,
        failure: CardReaderFailure,
    ) = logger.warn(
        LogField.safe("event", "ttp_reader_failed"),
        LogField.safe("phase", phase),
        LogField.safe("errorKind", failure.kind.diagnosticName),
        LogField.safe("errorCode", failure.code),
    ) { "the card reader failed" }
}
