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
 * state does not move, so bringing the reader up again is not what comes next. Whether to charge again is
 * a different question, and [capture] is what answers it: only [TapToPayCapture.NOT_CHARGED] means a
 * second charge cannot take the money twice.
 *
 * Three things expire the session: a reader session that is unusable, a device the vendor has refused, and
 * a charge that finds the stored device record gone. The last is not a reader condition at all, and it is
 * why this list is not two.
 */
public class TapToPayException internal constructor(
    message: String,
    cause: Throwable?,
    /**
     * The payment this failure belongs to, or null when no identifier was received.
     *
     * Null is not proof that nothing was opened: the call that opens a payment may have succeeded with its
     * answer lost, which is the case the attempt is kept for. It is the only handle to a payment that
     * exists, so a caller that means to reconcile one holds this.
     */
    public val paymentTransId: String? = null,
    /**
     * Whether the card was charged.
     *
     * Only [TapToPayCapture.NOT_CHARGED] means a retry cannot take the money twice.
     */
    public val capture: TapToPayCapture = TapToPayCapture.NOT_CHARGED,
) : Exception(message, cause)
