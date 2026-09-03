package com.payabli.sdk.taptopay

import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState

/**
 * A card-present call that did not succeed.
 *
 * What to do next is on [PayabliTTP.sessionState]. A failure that changed the session appears there as
 * [TapToPaySessionState.Failed] carrying a [TapToPayFailureReason], or as
 * [TapToPaySessionState.SessionExpired] when the reader session is spent.
 *
 * **A failure does not say whether the terminal is still usable; the state does.** Where the state has not
 * moved the terminal is still up, and where it reads [TapToPaySessionState.SessionExpired] a repair is what
 * comes next. A tap that did not complete produces either, so choosing between them from the failure alone
 * gets one of the two wrong.
 *
 * A repair does not always succeed on the next call: where the reader was refused, it succeeds once that
 * refusal is settled with the vendor.
 *
 * **`cause` is the SDK's own failure, and some of what it holds is displayable but not loggable.** The
 * service's `reason` text is written by the service rather than by this SDK, and it can echo what the
 * request carried. Showing it to a merchant is what it is for; sending the chain to a crash reporter is
 * not, and a reporter that reads fields rather than `toString` will find it. `toString` on those types
 * omits it.
 *
 * **A terminal that is still up is not the same as a charge that can be repeated.** Where a failure left it
 * unknown whether the payment was opened, the next charge carries the same attempt, so a charge for a
 * different amount is not what to send next. There is no call that resolves such an attempt yet.
 */
public class TapToPayException private constructor(
    message: String,
    cause: Throwable?,
    /**
     * The payment this failure belongs to, or null when no payment was opened.
     *
     * It is the only handle to a payment that exists, so a caller that means to reconcile one holds this.
     */
    public val paymentTransId: String? = null,
    /**
     * True when the card was charged and the payment was left open.
     *
     * The money has moved, so charging again takes it a second time. Finish this payment with
     * [PayabliTTP.closeCapturedCharge] instead, which needs no second tap.
     */
    public val captured: Boolean = false,
) : Exception(message, cause) {
    internal companion object {
        /**
         * The only way this type is constructed.
         *
         * `internal` is a Kotlin boundary and not a JVM one, so an internal constructor is callable from
         * Java. `@JvmSynthetic` is what closes that, and it cannot be applied to a constructor.
         */
        @JvmSynthetic
        internal fun of(
            message: String,
            cause: Throwable?,
            paymentTransId: String? = null,
            captured: Boolean = false,
        ): TapToPayException = TapToPayException(message, cause, paymentTransId, captured)
    }
}
