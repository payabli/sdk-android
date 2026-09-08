package com.payabli.sdk.taptopay.telemetry

import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import com.payabli.sdk.taptopay.session.diagnosticName
import java.util.concurrent.TimeUnit

/**
 * What card-present reports, in one place.
 *
 * The call sites name a phase and hand over what they already classified; the mapping to an event and to
 * the keys that event declares lives here. Spreading that over the session machine, the charge and the
 * reader is how a property drifts out of the catalog's allowlist and is dropped in silence.
 *
 * Nothing here suspends, and nothing here throws: [TelemetryRecorders] swallows what escapes, and a
 * reporting channel that can fail a charge is worse than no reporting channel.
 *
 * The recorder is the sessionless one, as the device routes and the attestation quota already are.
 * Card-present holds no [com.payabli.sdk.core.telemetry.TelemetrySessionContext]: nothing threads one
 * through to the reader or the coordinator. Passing one is worth doing and is its own change.
 */
internal object TapToPayReports {
    fun initializeStarted() = TelemetryRecorders.record(TelemetryEvents.TTP_INITIALIZE_STARTED)

    fun initializeSucceeded(startedAt: Long) = timed(TelemetryEvents.TTP_INITIALIZE_SUCCEEDED, startedAt)

    fun initializeFailed(
        failure: Throwable,
        startedAt: Long,
    ) = failed(TelemetryEvents.TTP_INITIALIZE_FAILED, failure, startedAt)

    fun attestationStarted() = TelemetryRecorders.record(TelemetryEvents.TTP_ATTESTATION_STARTED)

    fun attestationSucceeded(startedAt: Long) = timed(TelemetryEvents.TTP_ATTESTATION_SUCCEEDED, startedAt)

    fun attestationFailed(
        failure: Throwable,
        startedAt: Long,
    ) = failed(TelemetryEvents.TTP_ATTESTATION_FAILED, failure, startedAt)

    fun reinitializeStarted() = TelemetryRecorders.record(TelemetryEvents.TTP_REINITIALIZE_STARTED)

    fun reinitializeSucceeded(startedAt: Long) = timed(TelemetryEvents.TTP_REINITIALIZE_SUCCEEDED, startedAt)

    fun chargeStarted() = TelemetryRecorders.record(TelemetryEvents.TTP_CHARGE_STARTED)

    fun chargeSucceeded(startedAt: Long) = timed(TelemetryEvents.TTP_CHARGE_SUCCEEDED, startedAt)

    /**
     * A charge that did not succeed.
     *
     * **[cardWasAsked] decides whether a decline can still be one.** Before the reader is asked, a refusal
     * is the issuer refusing the payment. After it answers, the processor may already hold the card, and a
     * refusal arriving while the outcome is being recorded is a failure to record it rather than a payment
     * that was turned down. Reporting the second as `declined` would count a captured sale as a refused
     * one, in a property the card-not-present path shares.
     */
    fun chargeFailed(
        failure: Throwable,
        startedAt: Long,
        cardWasAsked: Boolean = false,
    ) = failed(TelemetryEvents.TTP_CHARGE_FAILED, failure, startedAt, canBeDeclined = !cardWasAsked)

    fun closeStarted() = TelemetryRecorders.record(TelemetryEvents.TTP_CLOSE_STARTED)

    fun closeSucceeded(startedAt: Long) = timed(TelemetryEvents.TTP_CLOSE_SUCCEEDED, startedAt)

    /**
     * A close that was not confirmed.
     *
     * **No close can be a decline**, which is why this takes no equivalent of [chargeFailed]'s
     * `cardWasAsked`. A close exists only once the reader has answered, so it is always on the far side of
     * the window that flag marks: a refusal here is a failure to record an outcome, never a payment the
     * issuer turned down.
     */
    fun closeFailed(
        failure: Throwable,
        startedAt: Long,
    ) = failed(TelemetryEvents.TTP_CLOSE_FAILED, failure, startedAt, canBeDeclined = false)

    fun nfcStarted() = TelemetryRecorders.record(TelemetryEvents.TTP_NFC_STARTED)

    fun nfcSucceeded(startedAt: Long) = timed(TelemetryEvents.TTP_NFC_SUCCEEDED, startedAt)

    /**
     * A reader refusal, by its kind and its code, and never by the vendor's words.
     *
     * Both, because they answer different questions. The kind is what this SDK decided to do about the
     * refusal; the code is which refusal it was. A kind of `unclassified` is the case where only the code
     * says anything, and a reader that timed out locally has a kind and no code at all.
     */
    fun nfcFailed(
        failure: CardReaderFailure,
        startedAt: Long,
    ) = TelemetryRecorders.record(TelemetryEvents.TTP_NFC_FAILED) {
        buildMap {
            put(TelemetryProperty.OUTCOME.key, TelemetryProperties.Outcome.FAILED)
            put(TelemetryProperty.REASON.key, failure.kind.diagnosticName)
            put(TelemetryProperty.DURATION_MS.key, elapsedMillis(startedAt).toString())
            failure.code?.let { put(TelemetryProperty.CODE.key, it) }
        }
    }

    /** [reason] only where the state carries one, so a move that failed says why and the rest do not. */
    fun sessionStateChanged(
        from: TapToPaySessionState,
        to: TapToPaySessionState,
    ) = TelemetryRecorders.record(TelemetryEvents.TTP_SESSION_STATE_CHANGED) {
        buildMap {
            put(TelemetryProperty.FROM.key, from.diagnosticName)
            put(TelemetryProperty.TO.key, to.diagnosticName)
            (to as? TapToPaySessionState.Failed)?.let {
                put(TelemetryProperty.REASON.key, it.reason.name.lowercase())
            }
        }
    }

    private fun timed(
        event: String,
        startedAt: Long,
    ) = TelemetryRecorders.record(event) {
        mapOf(TelemetryProperty.DURATION_MS.key to elapsedMillis(startedAt).toString())
    }

    private fun failed(
        event: String,
        failure: Throwable,
        startedAt: Long,
        canBeDeclined: Boolean = true,
    ) = TelemetryRecorders.record(event) {
        buildMap {
            put(TelemetryProperty.OUTCOME.key, outcomeOf(failure, canBeDeclined))
            put(TelemetryProperty.DURATION_MS.key, elapsedMillis(startedAt).toString())
            codeOf(failure)?.let { put(TelemetryProperty.CODE.key, it) }
        }
    }

    /**
     * `declined` means what the catalog says it means: **the payment was declined.** Everything else failed.
     *
     * The property is shared with the money path, so a value that means one thing in `:payin` and another
     * here makes any count of declines a mixture of two populations. A handset with no contactless radio and
     * a paypoint that is not set up for card-present are not declines: no payment was refused and usually
     * none was attempted.
     *
     * Read from the failure rather than from the session landing, which was the previous source and inverted
     * the two cases that matter. Landings answer "what should the session do now", and a service refusing a
     * card and a device that cannot take payments both end a session. `TTPTransactionException` is not a
     * `PayabliException`, so a real refusal fell to the landing map's else branch and was reported as failed,
     * while `DEVICE_INELIGIBLE` was reported as declined.
     *
     * Nothing is lost by the change: `reason` still names the kind where the event carries one, and `code`
     * still carries the vendor's.
     */
    private fun outcomeOf(
        failure: Throwable,
        canBeDeclined: Boolean,
    ): String =
        if (generateSequence(failure) { it.cause }.any { it.isCardRefusal() } ||
            (canBeDeclined && generateSequence(failure) { it.cause }.any { it.isDecline() })
        ) {
            TelemetryProperties.Outcome.DECLINED
        } else {
            TelemetryProperties.Outcome.FAILED
        }

    /**
     * A decline from either shape the service answers one in.
     *
     * The v2 envelope carries a refusal that the transaction client reads, and an HTTP 402 is turned into
     * [PayabliDeclineException] by the shared error table before that envelope is parsed. Both are the
     * issuer declining the card, and reading only the first reported the second as a failure.
     */
    private fun Throwable.isDecline(): Boolean =
        this is TTPTransactionException.Refused || this is PayabliDeclineException

    /**
     * A card the processor refused, which is a decline whenever it arrives.
     *
     * Separate from [isDecline] because the phase test does not apply to it. The two shapes above refuse the
     * opening, so after the reader has answered a decline-shaped failure is about the close rather than
     * about the card. This one can only happen once the card has been read, which is exactly the phase the
     * other test excludes, so sharing that test would report every refused card as a plain failure.
     */
    private fun Throwable.isCardRefusal(): Boolean = this is TTPTransactionException.CardRefused

    /**
     * The code a refusal carries, from either party that issues one.
     *
     * The reader's, and the service's from the v2 envelope. Both are fixed vocabularies; the words beside
     * them are not, and are never sent. Reading only the reader's left a decline reported as `declined`
     * with nothing saying which decline it was.
     */
    private fun codeOf(failure: Throwable): String? =
        generateSequence(failure) { it.cause }
            .firstNotNullOfOrNull {
                when (it) {
                    is CardReaderFailure -> it.code
                    is TTPTransactionException -> it.code
                    // The processor's own decline code, which is where it lands when the service answers
                    // 402 rather than a v2 envelope. Fixed vocabulary, like the two above.
                    is PayabliDeclineException -> it.rawCode
                    else -> null
                }
            }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
}
