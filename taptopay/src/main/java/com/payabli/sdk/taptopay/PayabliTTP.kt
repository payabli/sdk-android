package com.payabli.sdk.taptopay

import android.content.Context
import com.payabli.sdk.core.PayabliSession
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
 * `Error` is not caught at all.
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

    /** Brings the terminal up from wherever it stands. Safe to call again at any time. */
    public suspend fun initialize(): Unit = wrapping { coordinator.initialize() }

    /**
     * Spends the six-digit code the merchant was issued for this device.
     *
     * Leaves the session idle: the device is approved and nothing is set up, so [initialize] comes next.
     */
    public suspend fun activateDevice(activationCode: String): Unit =
        wrapping { coordinator.activateDevice(activationCode) }

    /**
     * Takes one payment. Waits for a card, so it runs as long as the person in front of the phone.
     *
     * [customer] has to identify the payer: an opening that names none is refused before a card is asked
     * for. Any one of `customerId`, `customerNumber`, `firstName` or `lastName` satisfies that, so a caller
     * holding only the identifier the service already knows does not have to invent a name to go with it.
     * What a given paypoint then requires is its own rule and is answered by the service.
     */
    public suspend fun charge(
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData,
        invoice: TapToPayInvoiceData = TapToPayInvoiceData(),
        orderDescription: String? = null,
    ): TapToPayResult = wrapping { runner.charge(paymentDetails, customer, invoice, orderDescription) }

    /**
     * Closes the payment named by [paymentTransId], where the card was charged and the close was not
     * confirmed.
     *
     * The card is not read again, so the person who paid does not tap twice, and no second payment is
     * opened. Sending a close that already landed costs nothing, which is what makes trying again safe.
     *
     * **Reachable from any [TapToPayCapture], not only [TapToPayCapture.CHARGED].** The transaction is open
     * at the service whatever the card did, so a payment whose card was refused or whose outcome was never
     * definite is worth closing too. The failure this raises carries the capture state the reader's answer
     * supports rather than assuming the money moved.
     *
     * What is closeable is the payment last taken for this entry point under this environment, by whichever
     * terminal took it. The record is shared across every terminal built for that pair, which is what lets a
     * screen that has been rebuilt finish a close the screen before it started. Nothing is kept once the
     * close lands, once a later payment is opened, or across process death.
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
         */
        @JvmStatic
        public fun isSupported(context: Context): Boolean =
            CardReaderEligibility(context).isSatisfied() && !looksEmulated()

        /**
         * Builds a terminal against [session].
         *
         * The entry point is the session's, read from what it publishes rather than taken again here, so a
         * terminal cannot be pointed at one the session was not configured with.
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
 * A withdrawn caller passes through: it is not a failure and must not be reported as one.
 *
 * A failure that already names its payment passes through too, since rebuilding it would drop what it
 * names.
 *
 * At file scope rather than on the instance because [PayabliTTP.create] needs it too, and a member of the
 * class is unreachable from the companion that builds one.
 */
private suspend fun <T> wrapping(block: suspend () -> T): T =
    try {
        block()
    } catch (withdrawn: CancellationException) {
        throw withdrawn
    } catch (named: TapToPayException) {
        throw named
    } catch (failure: Exception) {
        throw TapToPayException.of(failure.message ?: failure.javaClass.simpleName, failure)
    }
