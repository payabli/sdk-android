package com.payabli.sdk.taptopay

/**
 * A card-present call refused before anything was sent, for a reason a host can act on.
 *
 * An [IllegalStateException], because each one is a call made when the terminal could not take it; its own
 * type, because each is a different cause with its own catalog code.
 */
internal sealed class TapToPayCallException(
    message: String,
) : IllegalStateException(message) {
    /** The session did not reach ready, so there is no reader to charge on. */
    class TerminalNotReady : TapToPayCallException("the terminal is not ready")

    /** The session is ready and the device record it was built on is gone. */
    class NoDeviceToChargeAs : TapToPayCallException("the session is ready with no device to charge as")

    /** A close was asked for a payment whose answer is not held. */
    class PaymentNotHeld : TapToPayCallException("no captured payment is held under that identifier")
}
