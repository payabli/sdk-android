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
 * **A failure does not say whether the terminal is still usable; the state does.** Retrying the charge is
 * right where the state has not moved, and repairing the terminal is right where it reads
 * [TapToPaySessionState.SessionExpired]. A tap that did not complete produces either, so choosing between
 * them from the failure alone gets one of the two wrong.
 *
 * A repair does not always succeed on the next call: where the reader was refused, it succeeds once that
 * refusal is settled with the vendor.
 */
public class TapToPayException private constructor(
    message: String,
    cause: Throwable?,
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
        ): TapToPayException = TapToPayException(message, cause)
    }
}
