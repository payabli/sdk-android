package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.model.identifiesSomeone
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.network.sendableAmountOrNull
import com.payabli.sdk.taptopay.provider.CardReadOutcome
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.session.TapToPayChargeActivity
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
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
     * One payment at a time for this entry point, across every terminal built for it and every environment.
     *
     * Keyed by the entry point rather than held per instance, because that is what it protects. A terminal is
     * built per call, so two of them exist for one paypoint whenever a screen is rebuilt, and they share the
     * charge key by design. An instance mutex lets one settle that key while the other is mid-charge, after
     * which an ambiguous failure mints a fresh one and the payer can be charged twice.
     *
     * **Broader than [scope], which is what a payment belongs to.** The store matches on entry point
     * plus environment, so two environments for one paypoint hold separate keys. The lock stays keyed
     * on the entry point alone so two terminals for that paypoint cannot open, close or settle side by
     * side — including across environments, where [HELD] and the session repair still share the paypoint.
     */
    private val region: Mutex get() = regionFor(entry)

    suspend fun charge(
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData,
        invoice: TapToPayInvoiceData,
        orderDescription: String?,
    ): TapToPayResult =
        region.withLock {
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
            var resentKey = false
            // Never unset: once a card is asked for, no failure releases the key.
            var askedForCard = false
            try {
                coordinator.reinitializeIfNeeded()
                manager.charging(notReady = { TapToPayCallException.TerminalNotReady() }) {
                    // Lands failed: ending ready would send every retry back to this line.
                    val deviceId =
                        store.read(entry)?.deviceId ?: run {
                            manager.settle(TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED))
                            throw TapToPayCallException.NoDeviceToChargeAs()
                        }
                    // After the checks, so a charge that never reaches the wire holds no key.
                    val reservation = keys.reserve(entry, environment)
                    val idempotencyKey = reservation.key
                    reserved = idempotencyKey
                    resentKey = reservation.reused
                    if (resentKey) capture = TapToPayCapture.UNKNOWN
                    val paymentTransId =
                        client.initiate(
                            entryPoint = entry,
                            deviceId = deviceId,
                            paymentDetails = paymentDetails,
                            idempotencyKey = idempotencyKey,
                            customer = customer,
                            invoice = invoice,
                            orderDescription = orderDescription,
                        )
                    openedAs = paymentTransId
                    HELD.remove(scope)
                    logger.debug(
                        LogField.safe("event", "ttp_charge_opened"),
                        LogField.safe("phase", "initiate"),
                    ) { "the payment was opened" }

                    // Before the read: the processor takes the sale before the answer arrives.
                    askedForCard = true
                    capture = TapToPayCapture.UNKNOWN
                    manager.chargeActivity(TapToPayChargeActivity.WAITING_FOR_CARD)
                    val result = readCard(paymentTransId, sendable, invoice)

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
                                TapToPayReports.closeFailed(failure, closeStartedAt, TelemetryProperties.Origin.CHARGE)
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
            } catch (withdrawn: CancellationException) {
                // Ahead of the `Throwable` branch: a withdrawn caller is not a failure.
                throw withdrawn
            } catch (failure: Throwable) {
                // Only before the read: after it, no failure proves the money did not move.
                if (!askedForCard) {
                    val key = reserved
                    // Uncancellable, so a withdrawal cannot skip the arrival mark or the settle.
                    withContext(NonCancellable) {
                        if (key != null && resentKey && isConflict(failure)) {
                            keys.markArrived(entry, environment, key)
                        }
                        if (isAnswered(failure, resentKey)) {
                            key?.let { keys.settle(entry, environment, it) }
                        }
                    }
                }
                val type = hostTypeFor(failure, unclosed = failure === unclosed, cardWasAsked = askedForCard)
                // Before wrapping: the report classifies by the failure's own type.
                TapToPayReports.chargeFailed(failure, startedAt, cardWasAsked = askedForCard, type = type)
                // A JVM Error passes through unwrapped, as the facade promises.
                throw if (failure is Exception) failed(failure, type, openedAs, capture) else failure
            }
        }

    /**
     * Asks the reader for one card, and closes [paymentTransId] if it does not deliver one.
     *
     * [amount] is the value at the scale the paypoint recorded, so the card is asked for what was opened.
     *
     * **Every exit but a card leaves a transaction open at the service**, cancellation and a JVM `Error`
     * included, so every failure branch closes it on the way out. The `Error` is rethrown unchanged rather
     * than converted, so the facade's promise that one is not caught still holds for a caller: what the
     * branch exists for is the open payment it would otherwise leave standing.
     *
     * The key is never settled here. The reader has been asked by this point, so the sale may be captured
     * and nothing arriving afterwards says the money did not move.
     */
    private suspend fun readCard(
        paymentTransId: String,
        amount: BigDecimal,
        invoice: TapToPayInvoiceData,
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
            closeAfterFailedRead(paymentTransId, withdrawn)
            throw withdrawn
        } catch (failure: Throwable) {
            // A spent reader session is repaired by re-initializing. `invalidate` drops the move unless the
            // session is ready or charging, so a failure arriving while a replacement is being built does not
            // kill it.
            //
            // A denial expires it too, and the repair that follows lands DEVICE_INELIGIBLE.
            if (failure is CardReaderException.SessionUnusable ||
                failure is CardReaderException.DeviceDenied
            ) {
                manager.invalidate()
            }
            closeAfterFailedRead(paymentTransId, failure)
            throw failure
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
     */
    suspend fun closeCaptured(paymentTransId: String): Unit =
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
     * The catalog entry a charge's failure reaches the caller under.
     *
     * Once the reader has been asked for a card the sale may be captured, so a code saying nothing was sent
     * is not true of it, whatever the failure's own type: such a failure from then on is the payment's outcome
     * not being confirmed.
     */
    private fun hostTypeFor(
        failure: Throwable,
        unclosed: Boolean,
        cardWasAsked: Boolean,
    ): PayabliErrorType {
        if (unclosed) return PayabliErrorType.PAYMENT_NOT_CLOSED
        val type = TapToPayErrorCodes.typeFor(failure)
        val claimsNothingWasSent =
            type == PayabliErrorType.SDK_INTERNAL_ERROR || type == PayabliErrorType.VALIDATION_ERROR
        return if (cardWasAsked && claimsNothingWasSent) PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN else type
    }

    /** The failure a caller sees, under [type], carrying the payment it belongs to and whether the money moved. */
    private fun failed(
        failure: Exception,
        type: PayabliErrorType,
        paymentTransId: String?,
        capture: TapToPayCapture,
    ) = TapToPayErrorCodes.exceptionFor(failure, type, paymentTransId, capture)

    /**
     * Closes a transaction whose tap did not complete, best effort. The attempt stays named either way.
     *
     * Uncancellable: a withdrawn caller is one of the ways a tap does not complete, and the transaction is
     * open either way.
     *
     * **The key is never released here, and a recorded close is not a recorded failure.** The close makes
     * the service pull the processor for this transaction and write back what it finds, so a sale the
     * processor captured comes back captured rather than failed. This call sends the request and does not
     * decode the answer, so a close that landed says the service now knows the outcome and not what the
     * outcome was. Releasing on it would rotate the key after a capture and let the next charge take the
     * money again. Holding it keeps the repeat named as one attempt, which is the whole point of the key.
     */
    private suspend fun closeAfterFailedRead(
        paymentTransId: String,
        failure: Throwable,
    ) = withContext(NonCancellable) {
        manager.chargeActivity(TapToPayChargeActivity.CLOSING)
        val startedAt = System.nanoTime()
        TapToPayReports.closeStarted(TelemetryProperties.Origin.CHARGE)
        val closed =
            try {
                client.updateAfterFailedRead(paymentTransId, failure.javaClass.simpleName)
                true
            } catch (failedClose: Throwable) {
                TapToPayReports.closeFailed(failedClose, startedAt, TelemetryProperties.Origin.CHARGE)
                // `Throwable`, which is wider than this file catches anywhere else and is the width the caller
                // already uses. `readCard` catches `Throwable`, calls this, and rethrows what it caught, so a
                // failure raised *here* would replace the one being reported. An `Error` from the close would
                // then reach the host in place of the original, which is the opposite of the contract that a
                // JVM error is rethrown unchanged. This is the cleanup, so it is never the authoritative
                // failure.
                logger.warn(
                    LogField.safe("event", "ttp_charge_close_failed"),
                    LogField.safe("phase", "update"),
                    LogField.safe("errorKind", failedClose.javaClass.simpleName),
                ) { "an opened payment could not be closed after a failed tap" }
                false
            }
        if (closed) TapToPayReports.closeSucceeded(startedAt, TelemetryProperties.Origin.CHARGE)
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

    /** Whether [failure] is the service refusing a key it already holds. */
    private fun isConflict(failure: Throwable): Boolean =
        failure is PayabliException && failure.type == PayabliErrorType.CONFLICT
}
