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
 * state does not move, so a host retries the charge rather than bringing the reader up again. Only a reader
 * session that is unusable, or a device the vendor has refused, expires the session.
 */
public class TapToPayException internal constructor(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)
