package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import com.payabli.sdk.core.network.PayabliSendMark
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.model.identifiesSomeone
import com.payabli.sdk.taptopay.network.ChargeKeyRefusal
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.network.sendableAmountOrNull
import com.payabli.sdk.taptopay.provider.CardReadOutcome
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.session.REASON_RETIRED
import com.payabli.sdk.taptopay.session.TapToPayChargeActivity
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.telemetry.TapToPayReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** Enough that unrelated paypoints rarely share one, small enough to be a fixed cost. */
private const val REGION_STRIPES = 16

/** The service's wording when a close names a transaction the processor has no record of. */
private const val PROCESSOR_HAS_NO_RECORD = "Unable to locate the transaction with the payment processor"

/**
 * The charge regions, striped rather than one per entry point.
 *
 * Held here rather than on the runner for the reason [TapToPayChargeRunner.region] gives. Striped because
 * the entry point is a caller-supplied string and nothing bounds how many distinct ones a host passes, so a
 * map keyed on it grows with whatever it is handed. A fixed set of locks cannot.
 *
 * What striping costs is that two unrelated paypoints sharing a stripe wait for each other. What it keeps is
 * the only property that matters here: one entry point always resolves to the same lock, in this process and
 * in every terminal built in it.
 */
private val REGIONS: List<Mutex> = List(REGION_STRIPES) { Mutex() }

/** Masked rather than negated: `Int.MIN_VALUE` has no positive counterpart and negating it returns itself. */
private fun regionFor(entry: String): Mutex =
    REGIONS[(entry.hashCode().toLong() and 0x7fffffffL).toInt() % REGION_STRIPES]

/**
 * The charges and captured closes holding each entry point, from any terminal and environment. A charge claims
 * only an entry point nothing holds; a close counts itself and waits. Keyed exactly, so a shared stripe never refuses.
 */
private val HOLDERS = ConcurrentHashMap<String, Int>()

/** Removes one holder of [entry], and the entry with its last. */
private fun release(entry: String) {
    HOLDERS.compute(entry) { _, holders -> if (holders == null || holders <= 1) null else holders - 1 }
}

/**
 * The payment each scope has read a card for and not confirmed a close on.
 *
 * Keyed exactly rather than striped, because two scopes sharing a stripe share a lock and must not share a
 * payment. Unbounded growth is not the concern the regions have: an entry appears only once the reader has
 * answered under that scope, and it is removed when a close is confirmed or the next payment opens, so it
 * is bounded by paypoints a device has taken a card at rather than by anything a caller passes.
 *
 * **An approval is not what puts one here.** A refusal and an outcome that was never definite are retained
 * as well, because the transaction is open at the service whatever the card did and is worth closing. So
 * what waits here is the reader's answer to any tap that produced one, and how long it waits is a property
 * of that rather than of money having moved.
 *
 * Shared rather than held per runner for the reason the region is. A terminal is built per call, so a
 * payment retained by one and a payment retained by another are the same paypoint's, and an instance field
 * lets the second hide the first: both then believe they hold it, and whichever settles the attempt first
 * leaves the other to mint a fresh key and charge again.
 */
private val HELD = ConcurrentHashMap<String, PendingClose>()

private class PendingClose(
    val paymentTransId: String,
    val read: CardReadResult,
    /**
     * The attempt this payment was opened under.
     *
     * Settled once a close is confirmed and the reader's answer was definite — and only when this
     * payment was opened under a fresh key. A resent key names an earlier attempt; nothing this run
     * learns settles it.
     */
    val idempotencyKey: String,
    /** True when [idempotencyKey] was reused from an unsettled earlier attempt. */
    val resentKey: Boolean,
)

/** One payment, end to end: open it at Payabli, tap, close it. */
internal class TapToPayChargeRunner(
    private val entry: String,
    private val environment: PayabliEnvironment,
    private val coordinator: TapToPaySessionCoordinator,
    private val manager: TapToPaySessionManager,
    private val reader: TapToPayProvider,
    // Supplied rather than read inside the charge, so a test can hold the refusing side of it.
    private val contactlessEnabled: () -> Boolean,
    private val client: TTPTransactionClient,
    private val store: AttestedDeviceStore,
    private val keys: ChargeKeyStore,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * What a retained payment belongs to: the entry point, under the environment it was opened against.
     *
     * The entry point alone does not name a payment. A session that has reached
     * [com.payabli.sdk.core.SdkState.ReinitializeRequired] admits any configuration next, so one process can
     * hold a payment opened against one environment and then build a terminal for the same entry point
     * against another. Keyed on the entry point alone, that terminal finds the first payment and offers to
     * close it, sending an identifier and a processor answer to a service that never opened it.
     */
    private val scope: String = "${environment.name}/$entry"

    /**
     * Orders a captured close behind the charge on this entry point, which [HOLDERS] keeps to one. Keyed by the
     * entry point rather than the instance, since a terminal is built per call, and across environments, where
     * [HELD] and the session repair still share the paypoint.
     */
    private val region: Mutex get() = regionFor(entry)

    suspend fun charge(
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData,
        invoice: TapToPayInvoiceData,
        orderDescription: String?,
        attempt: PayabliAttempt = PayabliAttempt.NewPayment,
    ): TapToPayResult =
        run {
            val sendable = sendableAmountOf(paymentDetails)
            // The service refuses this only after a card is taken.
            requireArgument(customer.identifiesSomeone) { "a charge has to name the payer it is for" }

            // After the precondition, so a bad argument is not reported as a failed charge.
            val startedAt = System.nanoTime()
            var openedAs: String? = null
            var capture = TapToPayCapture.NOT_CHARGED
            var unclosed: Throwable? = null
            TapToPayReports.chargeStarted()

            var reserved: String? = null
            // Set by the transport as it hands the opening over, so a failure before that is known not to have left.
            val sendMark = PayabliSendMark()
            var resentKey = false
            var arrivedAt: Long? = null
            // Never unset: once a card is asked for, no failure releases the key.
            var askedForCard = false
            var claimed = false
            var locked = false
            try {
                // Claimed before the region, so a second charge on this entry point is refused here and never
                // reaches the lock.
                claimed = HOLDERS.putIfAbsent(entry, 1) == null
                if (!claimed) {
                    // One read, so the running charge cannot settle the key between the refusal and its capture.
                    val found =
                        if (attempt == PayabliAttempt.Retry) {
                            keys.heldRetryOf(entry, environment, ChargeDescription.of(paymentDetails, invoice))
                        } else {
                            null
                        }
                    if (attempt == PayabliAttempt.Retry && found?.resent != false) capture = TapToPayCapture.UNKNOWN
                    throw overlapRefusal(found)
                }
                // Locked inside the try, so the refusal above reaches the same catch and finally.
                region.lock()
                locked = true
                // At the call, not by listening: the adapter's state broadcasts reach no app.
                if (!contactlessEnabled()) {
                    throw PayabliGenericException(
                        PayabliErrorType.CONTACTLESS_DISABLED,
                        PayabliErrorType.CONTACTLESS_DISABLED.message,
                    )
                }
                // Inside the session region from the repair to the close, so no setup rebuilds the session
                // under the read.
                coordinator.runCharge {
                    manager.charging(notReady = { TapToPayCallException.TerminalNotReady() }) {
                        // Lands failed: ending ready would send every retry back to this line.
                        val deviceId =
                            store.read(entry)?.deviceId ?: run {
                                manager.failCharge(TapToPayFailureReason.DEVICE_SETUP_REQUIRED)
                                throw TapToPayCallException.NoDeviceToChargeAs()
                            }
                        // After the checks, so a charge that never reaches the wire holds no key.
                        val reservation =
                            keys.reserve(entry, environment, attempt, ChargeDescription.of(paymentDetails, invoice))
                        val idempotencyKey = reservation.key
                        reserved = idempotencyKey
                        resentKey = reservation.reused
                        if (resentKey) capture = TapToPayCapture.UNKNOWN
                        arrivedAt = reservation.reservedAt
                        val paymentTransId =
                            withContext(sendMark) {
                                client.initiate(
                                    entryPoint = entry,
                                    deviceId = deviceId,
                                    paymentDetails = paymentDetails,
                                    idempotencyKey = idempotencyKey,
                                    customer = customer,
                                    invoice = invoice,
                                    orderDescription = orderDescription,
                                )
                            }
                        openedAs = paymentTransId
                        HELD.remove(scope)
                        logger.debug(
                            LogField.safe("event", "ttp_charge_opened"),
                            LogField.safe("phase", "initiate"),
                        ) { "the payment was opened" }

                        // Again here, because a replacement can land while the payment opens.
                        if (!coordinator.isSessionLive()) {
                            refuseRetiredCharge(paymentTransId, idempotencyKey, resentKey)
                        }

                        // Before the read: the processor takes the sale before the answer arrives.
                        askedForCard = true
                        capture = TapToPayCapture.UNKNOWN
                        manager.chargeActivity(TapToPayChargeActivity.WAITING_FOR_CARD)
                        val result = readCard(paymentTransId, sendable, invoice, idempotencyKey, resentKey)

                        capture = captureOf(result.outcome, resentKey)
                        HELD[scope] = PendingClose(paymentTransId, result, idempotencyKey, resentKey)

                        // Uncancellable: the card is taken and this close is the only call that tells the service.
                        // The settle and the held payment's removal are inside for the same reason.
                        val closeFailure =
                            withContext(NonCancellable) {
                                manager.chargeActivity(TapToPayChargeActivity.CLOSING)
                                val closeStartedAt = System.nanoTime()
                                TapToPayReports.closeStarted(TelemetryProperties.Origin.CHARGE)
                                try {
                                    client.update(paymentTransId, result)
                                } catch (withdrawn: CancellationException) {
                                    throw withdrawn
                                } catch (failure: Exception) {
                                    TapToPayReports.closeFailed(
                                        failure,
                                        closeStartedAt,
                                        TelemetryProperties.Origin.CHARGE,
                                    )
                                    // A declined card settles whatever its close was refused with. An indeterminate
                                    // read's authorisation may still be handling at the processor, so no answer
                                    // to its close settles it.
                                    if (!resentKey && result.outcome == CardReadOutcome.DECLINED) {
                                        keys.settle(entry, environment, idempotencyKey)
                                    }
                                    return@withContext failure
                                }
                                TapToPayReports.closeSucceeded(closeStartedAt, TelemetryProperties.Origin.CHARGE)
                                // A resent key names an earlier opening, so this run's answer never settles it.
                                if (!resentKey && result.outcome != CardReadOutcome.INDETERMINATE) {
                                    keys.settle(entry, environment, idempotencyKey)
                                }
                                HELD.remove(scope)
                                null
                            }

                        unclosed = closeFailure

                        // A failed close travels suppressed on a refusal, never in its place.
                        when (result.outcome) {
                            CardReadOutcome.APPROVED -> {
                                closeFailure?.let { throw it }
                                TapToPayResult(paymentTransId = paymentTransId, cardNetwork = result.cardNetwork)
                                    .also { TapToPayReports.chargeSucceeded(startedAt) }
                            }

                            CardReadOutcome.DECLINED ->
                                throw TTPTransactionException.CardRefused(result.providerState).apply {
                                    closeFailure?.let(::addSuppressed)
                                }

                            CardReadOutcome.INDETERMINATE ->
                                throw closeFailure ?: TTPTransactionException.OutcomeUnknown(result.providerState)
                        }
                    }
                }
            } catch (withdrawn: CancellationException) {
                // Ahead of the `Throwable` branch: a withdrawn caller is not a failure. Withdrawn before the card, a
                // fresh key protects no sale, so it is released and the earlier unknown sale's key stays claimable.
                if (!askedForCard && capture == TapToPayCapture.NOT_CHARGED) {
                    reserved?.let { withContext(NonCancellable) { keys.settle(entry, environment, it) } }
                }
                // Withdrawn before the opening was handed over, a resend wrote nothing, so its key's clock stands.
                if (!askedForCard && !sendMark.mayHaveLeft) arrivedAt = null
                throw withdrawn
            } catch (failure: Throwable) {
                // A refused key extends no marker an earlier send wrote, and a send that never left writes none.
                // Any other answer may have reached the key check, and restarting errs toward holding the key.
                if (!askedForCard && openedAs == null && (failure is ChargeKeyRefusal || !sendMark.mayHaveLeft)) {
                    arrivedAt = null
                }
                // Only before the read: after it, no failure proves the money did not move.
                if (!askedForCard) {
                    val key = reserved
                    // Uncancellable, so a withdrawal cannot skip the settle.
                    withContext(NonCancellable) {
                        // The host is told this opening was not charged, so its key protects no sale.
                        if (capture == TapToPayCapture.NOT_CHARGED || isAnswered(failure, resentKey)) {
                            key?.let { keys.settle(entry, environment, it) }
                        }
                    }
                }
                // A retry refused before its key was reserved still describes the held sale, whose outcome is unknown.
                // Only once claimed: then no other charge on this entry point can settle the key under the read.
                if (claimed && reserved == null && attempt == PayabliAttempt.Retry) {
                    val described = ChargeDescription.of(paymentDetails, invoice)
                    if (withContext(NonCancellable) { keys.holdsRetryOf(entry, environment, described) }) {
                        capture = TapToPayCapture.UNKNOWN
                    }
                }
                val type =
                    hostTypeFor(
                        failure,
                        unclosed = failure === unclosed,
                        cardWasAsked = askedForCard,
                        resentKey = resentKey,
                    )
                // Before wrapping: the report classifies by the failure's own type.
                TapToPayReports.chargeFailed(failure, startedAt, cardWasAsked = askedForCard, type = type)
                // The code that says the payment was not taken is the one answer whose capture is known: the
                // card was not charged, so a retry cannot take the money twice.
                if (type == PayabliErrorType.PAYMENT_NOT_TAKEN) capture = TapToPayCapture.NOT_CHARGED
                // A JVM Error passes through unwrapped, as the facade promises.
                throw if (failure is Exception) failed(failure, type, openedAs, capture) else failure
            } finally {
                // A key still held names the newest unsettled charge, the only one a retry can claim.
                try {
                    reserved?.let { withContext(NonCancellable) { keys.supersede(entry, environment, it, arrivedAt) } }
                } finally {
                    if (locked) region.unlock()
                    if (claimed) release(entry)
                }
            }
        }

    /**
     * Asks for one card for [amount], at the scale the paypoint recorded. Every other exit, cancellation
     * and a JVM `Error` included, closes [paymentTransId] and rethrows what it caught. A minted-key run's
     * `Exception` gives way to the close's no-record refusal, which settles the key; an `Error` never does.
     */
    private suspend fun readCard(
        paymentTransId: String,
        amount: BigDecimal,
        invoice: TapToPayInvoiceData,
        idempotencyKey: String,
        resentKey: Boolean,
    ): CardReadResult =
        try {
            reader.startReading(
                CardReadRequest(
                    amount = amount,
                    merchantTransactionId = paymentTransId,
                    merchantOrderId = paymentTransId,
                    merchantInvoiceNumber = invoice.invoiceNumber,
                ),
            )
        } catch (withdrawn: CancellationException) {
            closeAfterFailedRead(paymentTransId, withdrawn, idempotencyKey, resentKey)
            throw withdrawn
        } catch (failure: Throwable) {
            val noRecord = closeAfterFailedRead(paymentTransId, failure, idempotencyKey, resentKey)
            // After the close, which publishes CLOSING only while the charge holds the session. Expires this
            // terminal's session, where no setup runs during a charge; a denial's repair lands DEVICE_INELIGIBLE.
            if (failure is CardReaderException.SessionUnusable ||
                failure is CardReaderException.DeviceDenied
            ) {
                manager.invalidate()
            }
            // A switched-off radio ends the charge on the contactless-disabled reason; the reader is
            // re-armed by the next charge's repair, once the radio is on.
            if (failure is CardReaderException.ContactlessDisabled) {
                manager.failCharge(TapToPayFailureReason.CONTACTLESS_DISABLED)
            }
            // A JVM Error is never converted into a payment outcome: it keeps its place, the close's
            // refusal suppressed on it, the way a failed close rides on a refused card.
            if (noRecord != null && failure is Error) {
                failure.addSuppressed(noRecord)
                throw failure
            }
            throw noRecord ?: failure
        }

    /**
     * The amount [paymentDetails] will actually send, once both of its values have been checked.
     *
     * **Checked at the scale it will be sent at.** `0.001` is above zero and reaches the wire as `0.00`, so
     * the raw value passing says nothing about what the paypoint records. The service fee takes the same
     * rounding through the same serializer; zero is allowed there and below zero is not.
     */
    private fun sendableAmountOf(paymentDetails: TapToPayPaymentDetails): BigDecimal {
        val sendable =
            requireArgumentNotNull(paymentDetails.amount.sendableAmountOrNull()) {
                "an amount has to be one this SDK can send"
            }
        requireArgument(sendable > BigDecimal.ZERO) { "an amount has to be greater than zero" }

        val sendableFee =
            requireArgumentNotNull(paymentDetails.serviceFee.sendableAmountOrNull()) {
                "a service fee has to be one this SDK can send"
            }
        requireArgument(sendableFee >= BigDecimal.ZERO) { "a service fee cannot be negative" }
        return sendable
    }

    /**
     * Retries the unconfirmed close of a retained payment.
     *
     * Takes no second tap: the reader already answered and its answer was kept, whatever that answer was.
     * Refuses anything but the payment currently held, so a mistyped identifier cannot close a payment this
     * SDK has no answer for.
     *
     * Whether the first close reached the service is not known here and does not need to be. Sending one
     * that already applied costs nothing, and a response that never arrived may have followed a close that
     * did apply, so an unconfirmed close is what this retries rather than a failed one.
     *
     * Holds the entry point from the call, so a charge arriving while this runs or waits is refused; this
     * itself waits for a charge already under way.
     *
     * @param paymentTransId the payment to close, which has to be the one held for this entry point and environment.
     */
    suspend fun closeCaptured(paymentTransId: String) {
        HOLDERS.merge(entry, 1, Int::plus)
        try {
            closeHeld(paymentTransId)
        } finally {
            release(entry)
        }
    }

    private suspend fun closeHeld(paymentTransId: String): Unit =
        region.withLock {
            val pending = HELD[scope]
            if (pending == null || pending.paymentTransId != paymentTransId) {
                // Named rather than left to the facade's default. A caller reaches this by asking to close
                // a payment whose answer is no longer held, and the default would report it as never
                // charged, which is the one thing this SDK must not say about a payment it cannot account
                // for. It carries the identifier it was given, and unknown, because that is what is true.
                val refused = TapToPayCallException.PaymentNotHeld()
                throw failed(
                    refused,
                    TapToPayErrorCodes.typeFor(refused),
                    paymentTransId,
                    TapToPayCapture.UNKNOWN,
                )
            }
            val startedAt = System.nanoTime()
            TapToPayReports.closeStarted(TelemetryProperties.Origin.RETRY)
            try {
                withContext(NonCancellable) {
                    client.update(pending.paymentTransId, pending.read)
                    // The same rule as the charge's own close, on the same reader answer. A held payment
                    // is kept for every outcome, because the transaction is open at the service whatever
                    // the card did, so a recovery can be closing one whose outcome was never definite.
                    // Settling that would drop the only handle on an attempt that may have taken money.
                    // A resent key is never settled from what this close learns about a later opening.
                    if (!pending.resentKey && pending.read.outcome != CardReadOutcome.INDETERMINATE) {
                        keys.settle(entry, environment, pending.idempotencyKey)
                    }
                    // The close landed either way, so nothing is left to recover.
                    HELD.remove(scope)
                }
            } catch (withdrawn: CancellationException) {
                // Converting it would hide it from the facade, which reads the type to decide what to
                // rethrow.
                throw withdrawn
            } catch (failure: Exception) {
                TapToPayReports.closeFailed(failure, startedAt, TelemetryProperties.Origin.RETRY)
                // Still held, so this can be tried again.
                throw failed(
                    failure,
                    PayabliErrorType.PAYMENT_NOT_CLOSED,
                    pending.paymentTransId,
                    captureOf(pending.read.outcome, pending.resentKey),
                )
            }
            TapToPayReports.closeSucceeded(startedAt, TelemetryProperties.Origin.RETRY)
        }

    /**
     * What the reader's answer says about the money, which is not the same as whether it answered.
     *
     * Read by the charge and by the recovery, so the two cannot disagree about a payment they both saw.
     * [resentKey] clamps a refusal to unknown: the refusal is about this run, and the key names an earlier
     * one.
     */
    private fun captureOf(
        outcome: CardReadOutcome,
        resentKey: Boolean = false,
    ): TapToPayCapture {
        val reported =
            when (outcome) {
                CardReadOutcome.APPROVED -> TapToPayCapture.CHARGED
                CardReadOutcome.DECLINED -> TapToPayCapture.NOT_CHARGED
                CardReadOutcome.INDETERMINATE -> TapToPayCapture.UNKNOWN
            }
        return if (resentKey && reported == TapToPayCapture.NOT_CHARGED) {
            TapToPayCapture.UNKNOWN
        } else {
            reported
        }
    }

    /**
     * The catalog entry a charge's failure reaches the caller under. Once the reader has been asked for
     * a card the sale may be captured, so whatever the failure's own type, a code saying nothing was sent
     * is not true of it: such a failure is the payment's outcome not being confirmed.
     */
    private fun hostTypeFor(
        failure: Throwable,
        unclosed: Boolean,
        cardWasAsked: Boolean,
        resentKey: Boolean,
    ): PayabliErrorType {
        // A refusal saying the processor has no record of the payment is about the close of a failed
        // read: a run that minted its key, asked for the card and never saw the sale charged. A completed
        // read's close is the payment not being closed, and a resent key names an earlier attempt.
        if (!resentKey &&
            cardWasAsked &&
            !unclosed &&
            failure.saysTheProcessorHasNoRecord()
        ) {
            return PayabliErrorType.PAYMENT_NOT_TAKEN
        }
        if (unclosed) return PayabliErrorType.PAYMENT_NOT_CLOSED
        // An opening refused as a repeat of this SDK's own resent key: an earlier charge has not finished.
        if (!cardWasAsked && resentKey && failure is ChargeKeyRefusal) return PayabliErrorType.CHARGE_NOT_FINISHED
        val type = TapToPayErrorCodes.typeFor(failure)
        val claimsNothingWasSent =
            type == PayabliErrorType.SDK_INTERNAL_ERROR || type == PayabliErrorType.VALIDATION_ERROR
        return if (cardWasAsked && claimsNothingWasSent) PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN else type
    }

    /**
     * Why a charge is refused while another on its entry point is under way.
     *
     * @param found the held key a refused retry found, or null for a new payment or when none is held.
     * @return a payment-reference refusal for a retry whose key is under way, otherwise the terminal not being ready.
     */
    private fun overlapRefusal(found: ChargeKeyStore.HeldRetry?): Exception =
        if (found?.underWay == true) {
            PayabliGenericException(
                PayabliErrorType.PAYMENT_REFERENCE_OPEN,
                PayabliErrorType.PAYMENT_REFERENCE_OPEN.message,
            )
        } else {
            TapToPayCallException.TerminalNotReady()
        }

    /** The failure a caller sees, under [type], carrying the payment it belongs to and whether the money moved. */
    private fun failed(
        failure: Exception,
        type: PayabliErrorType,
        paymentTransId: String?,
        capture: TapToPayCapture,
    ) = TapToPayErrorCodes.exceptionFor(failure, type, paymentTransId, capture)

    /**
     * Ends a charge whose session was replaced once [paymentTransId] opened: no card is read, the close is
     * attempted, and the terminal lands [TapToPayFailureReason.SESSION_ENDED].
     */
    private suspend fun refuseRetiredCharge(
        paymentTransId: String,
        idempotencyKey: String,
        resentKey: Boolean,
    ): Nothing {
        val refusal = PayabliGenericException(PayabliErrorType.SESSION_NOT_INITIALIZED, REASON_RETIRED)
        closeAfterFailedRead(paymentTransId, refusal, idempotencyKey, resentKey)
        manager.failCharge(TapToPayFailureReason.SESSION_ENDED)
        throw refusal
    }

    /**
     * Closes an uncompleted tap, uncancellably. A close that lands keeps the key, because the answer is not
     * decoded and a capture reads like a failure. The one release is the refusal saying the processor has no
     * record of the payment, on a run that minted its key.
     *
     * @return the close's no-record refusal on a run that minted its key, or null.
     */
    private suspend fun closeAfterFailedRead(
        paymentTransId: String,
        failure: Throwable,
        idempotencyKey: String,
        resentKey: Boolean,
    ) = withContext(NonCancellable) {
        manager.chargeActivity(TapToPayChargeActivity.CLOSING)
        val startedAt = System.nanoTime()
        TapToPayReports.closeStarted(TelemetryProperties.Origin.CHARGE)
        var noRecordRefusal: Throwable? = null
        val closed =
            try {
                client.updateAfterFailedRead(paymentTransId, failure.javaClass.simpleName)
                true
            } catch (failedClose: Throwable) {
                TapToPayReports.closeFailed(failedClose, startedAt, TelemetryProperties.Origin.CHARGE)
                // The processor holding no record of the payment means nothing was captured. A resent key names
                // an earlier opening, so this answer never settles it.
                if (!resentKey && failedClose.saysTheProcessorHasNoRecord()) {
                    keys.settle(entry, environment, idempotencyKey)
                }
                // On a run that minted its key, the refusal naming this payment one the processor holds no
                // record of is about the charge, so it alone is handed back to the caller. Anything else
                // raised here stays swallowed, an `Error` included.
                logger.warn(
                    LogField.safe("event", "ttp_charge_close_failed"),
                    LogField.safe("phase", "update"),
                    LogField.safe("errorKind", failedClose.javaClass.simpleName),
                ) { "an opened payment could not be closed after a failed tap" }
                noRecordRefusal = failedClose.takeIf { !resentKey && it.saysTheProcessorHasNoRecord() }
                false
            }
        if (closed) TapToPayReports.closeSucceeded(startedAt, TelemetryProperties.Origin.CHARGE)
        noRecordRefusal
    }

    /**
     * Whether [failure] says nothing was opened, so its key can be let go.
     *
     * Read only before the reader has answered. The service refusing, declining or reporting the paypoint
     * unequipped are answers about the opening, so what comes next is a new attempt and a held key would
     * refuse it. Anything else is kept.
     *
     * **Nothing settles a resent key.** That key names an earlier attempt, and nothing this opening is told
     * is about that attempt. What releases it is the attempt expiring, or the payment it names being closed.
     *
     * Kept rather than released is the safe direction, so this answers true only for what it recognises.
     */
    private fun isAnswered(
        failure: Throwable,
        resentKey: Boolean,
    ): Boolean =
        !resentKey &&
            when (failure) {
                is CancellationException -> false
                is TTPTransactionException.Refused,
                is TTPTransactionException.ServiceRejected,
                is TTPTransactionException.NotEnabled,
                -> true

                is PayabliException -> !failure.type.leavesOutcomeUnknown
                else -> false
            }

    private fun Throwable.saysTheProcessorHasNoRecord(): Boolean =
        this is PayabliValidationException && detail == PROCESSOR_HAS_NO_RECORD
}
