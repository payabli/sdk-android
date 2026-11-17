package com.payabli.sdk.taptopay

import android.content.Context
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.taptopay.adapters.platform.CardReaderEligibility
import com.payabli.sdk.taptopay.adapters.platform.TapToPayComponents
import com.payabli.sdk.taptopay.adapters.platform.looksEmulated
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Card-present acceptance, for one paypoint.
 *
 * Every call that fails fails with [TapToPayException], with two exceptions a caller has to know about:
 * a cancellation unwinds as `CancellationException`, because withdrawing is not a failure, and a JVM
 * `Error` other than a linkage failure is not caught at all.
 *
 * **A withdrawn charge is the one place that is not the whole story.** Once the card has been taken, the
 * call that tells the service is uncancellable, so a cancellation arriving after that point can still end
 * in a completed payment and [charge] returns its result. A host that treats a cancelled charge as one that
 * did not happen will be wrong exactly when money moved.
 *
 * A failure that changed the session is published on [sessionState]: as
 * [TapToPaySessionState.Failed] carrying a reason, or as [TapToPaySessionState.SessionExpired] where the
 * reader session is spent and a repair is what comes next. A failure that changed nothing leaves it alone.
 * Read the state rather than assuming which of the two a failure produced.
 */
public class PayabliTTP private constructor(
    private val coordinator: TapToPaySessionCoordinator,
    private val runner: TapToPayChargeRunner,
) {
    /** Where the terminal has got to. Safe to collect at any time. */
    public val sessionState: StateFlow<TapToPaySessionState> get() = coordinator.state

    /** Whether a payment can be taken right now. */
    public val isReady: StateFlow<Boolean> get() = coordinator.isReady

    /** Brings the terminal up from wherever it stands. Safe to call again while no charge is running. */
    public suspend fun initialize(): Unit = wrapping { coordinator.initialize() }

    /**
     * Spends the six-digit code the merchant was issued for this device.
     *
     * Leaves the session idle: the device is approved and nothing is set up, so [initialize] comes next.
     *
     * A failure the SDK did not classify converts to the SDK's own failure, as at every entry point, and
     * is reported nowhere: no failure event exists for an activation.
     */
    public suspend fun activateDevice(activationCode: String): Unit =
        wrapping { coordinator.activateDevice(activationCode) }

    /**
     * Takes one payment. Waits for a card, so it runs as long as the person in front of the phone.
     *
     * [customer] is sent as given, an empty one included. A paypoint that requires customer fields the charge
     * did not carry refuses it before any card is read, with [TapToPayException.refusal] set to
     * [TapToPayChargeRefusal.CustomerDataRefused] naming them. [TapToPayException.capture] is not charged, or
     * unknown on a retry that resent a held key, which stays held.
     *
     * **A charge is a new sale unless [attempt] says it is a retry.** A charge whose outcome is unknown keeps
     * its idempotency key for this entry point under this environment, and only a charge declared
     * [PayabliAttempt.Retry] is sent the most recent of those keys, so the service recognises the repeat
     * rather than opening a second sale. With none held, or one the service would no longer recognise, it is
     * sent under a new key. A retry the service refuses because the earlier sale reached it fails as
     * [com.payabli.sdk.core.model.PayabliErrorType.CHARGE_NOT_FINISHED].
     *
     * A charge made with the device's contactless radio switched off is refused with
     * [com.payabli.sdk.core.model.PayabliErrorType.CONTACTLESS_DISABLED] before anything is sent.
     *
     * @param attempt whether this is a new sale or a retry of the most recent sale whose outcome is not known.
     */
    public suspend fun charge(
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData = TapToPayCustomerData(),
        invoice: TapToPayInvoiceData = TapToPayInvoiceData(),
        orderDescription: String? = null,
        attempt: PayabliAttempt = PayabliAttempt.NewPayment,
    ): TapToPayResult = wrapping { runner.charge(paymentDetails, customer, invoice, orderDescription, attempt) }

    /**
     * Closes the payment named by [paymentTransId], with no second tap and no second payment.
     *
     * A close that already applied costs nothing to send again, which is what makes retrying safe, and is
     * why this does not turn on whether an earlier close reached the service. It cannot: a response that
     * never arrived may have followed a close that applied.
     *
     * **Any identifier this entry point produced can be closed**, including one kept from before the process
     * restarted, one taken under another environment, and one whose charge failed before the reader answered.
     * The payment this SDK still holds for this entry point and environment is closed with the reader's
     * answer it kept, and the failure this raises carries the capture state that answer supports. Any other
     * identifier is closed without one, and its failure carries [TapToPayCapture.UNKNOWN]. A close the caller
     * may not send fails with [com.payabli.sdk.core.model.PayabliErrorType.PERMISSION_DENIED], which a retry
     * does not change.
     *
     * What is held is the payment last taken for this entry point under this environment, by whichever
     * terminal took it, and only until a close is confirmed or a later payment is opened. A call made while a
     * charge on this entry point runs waits for that charge.
     *
     * @param paymentTransId the payment to close.
     */
    public suspend fun closeCapturedCharge(paymentTransId: String): Unit =
        wrapping { runner.closeCaptured(paymentTransId) }

    public companion object {
        /**
         * Whether this device can take card-present payments, answered before anything is built.
         *
         * Needs no session, no credentials and no network, so a host can decide whether to offer
         * card-present at all before it has any of them.
         *
         * **A signal, not a gate.** It answers from what the device reports about itself: the platform
         * version, whether a contactless radio is present, whether the ABI the card reader ships native
         * code for is offered, and whether the build looks like an emulator image. None of that can see a
         * paypoint that is not enabled for card-present, a device the vendor has refused, or a reader that
         * fails once it is armed, so `false` is reliable and `true` is not a promise. The SDK does not
         * consult this: [create] and [initialize] run whatever it says, and report what actually happened.
         *
         * A failure the SDK did not classify converts to the SDK's own failure, as at every entry point,
         * and is reported nowhere: no failure event exists for an eligibility answer.
         */
        @JvmStatic
        public fun isSupported(context: Context): Boolean =
            wrapping { CardReaderEligibility(context).isSatisfied() && !looksEmulated() }

        /**
         * Builds a terminal against [session].
         *
         * The entry point is the session's, read from what it publishes rather than taken again here, so a
         * terminal cannot be pointed at one the session was not configured with.
         *
         * A failure the SDK did not classify converts to the SDK's own failure, as at every entry point,
         * and is reported nowhere: building a terminal belongs to no failure event.
         */
        public suspend fun create(
            session: PayabliSession,
            context: Context,
        ): PayabliTTP = wrapping { TapToPayComponents.build(session, context) }

        /**
         * The only way a terminal is constructed.
         *
         * [coordinator] and [runner] have to share a session, and nothing downstream re-checks it.
         * `internal` is a Kotlin boundary and not a JVM one, so an internal constructor is callable from
         * Java; `@JvmSynthetic` is what closes that, and it cannot be applied to a constructor.
         */
        @JvmSynthetic
        internal fun over(
            coordinator: TapToPaySessionCoordinator,
            runner: TapToPayChargeRunner,
        ): PayabliTTP = PayabliTTP(coordinator, runner)
    }
}

/**
 * The entry points' one ladder: a withdrawal and a failure that already names its payment pass
 * through, an unclassified exception or linkage failure converts, and any other `Error` is not caught;
 * `inline` rather than `suspend`, so the non-suspending [PayabliTTP.isSupported] shares it too.
 *
 * @param block the call an entry point makes.
 * @return what the call returns.
 */
private inline fun <T> wrapping(block: () -> T): T =
    try {
        block()
    } catch (withdrawn: CancellationException) {
        throw withdrawn
    } catch (named: TapToPayException) {
        throw named
    } catch (failure: Exception) {
        throw converted(failure)
    } catch (failure: LinkageError) {
        throw converted(failure)
    }

/** The failure an entry point answers an unclassified original with, under the codes' classification. */
private fun converted(failure: Throwable): TapToPayException =
    TapToPayErrorCodes.exceptionFor(
        failure,
        TapToPayErrorCodes.typeFor(failure),
        paymentTransId = null,
        capture = TapToPayCapture.NOT_CHARGED,
    )
