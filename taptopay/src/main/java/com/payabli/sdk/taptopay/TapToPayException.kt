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
 * **A tap that did not complete is not one of them.** The reader session it ran on is unaffected and the
 * state does not move, so a host retries the charge rather than bringing the reader up again.
 *
 * Three things expire the session: a reader session that is unusable, a device the vendor has refused, and
 * a charge that finds the stored device record gone. The last is not a reader condition at all, and it is
 * why this list is not two.
 */
public class TapToPayException internal constructor(
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
) : Exception(message, cause)
