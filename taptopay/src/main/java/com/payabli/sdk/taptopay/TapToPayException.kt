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
 * **An ordinary tap that did not complete is not one of them.** A card withdrawn, a card refused, a read
 * that timed out: the reader session it ran on is unaffected and the state does not move, so a host retries
 * the charge and does not bring the reader up again.
 *
 * **Two failures during a tap do move it, and retrying either of them gets nowhere.** A reader session that
 * has become unusable, and a device the vendor has refused. Both land on [TapToPaySessionState.SessionExpired]
 * and both need the terminal brought up again, which for a refused device only succeeds once the refusal is
 * settled with the vendor. A charge that finds the stored device record gone expires the session too, and it
 * is not a reader condition at all, which is why that list is three.
 *
 * So the state is what says which happened. Reading the failure alone cannot tell a withdrawn card from a
 * denied device.
 */
public class TapToPayException internal constructor(
    message: String,
    cause: Throwable?,
) : Exception(message, cause)
