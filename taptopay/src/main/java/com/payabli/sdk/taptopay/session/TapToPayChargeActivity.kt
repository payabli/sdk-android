package com.payabli.sdk.taptopay.session

/**
 * What a running charge is doing, carried by [TapToPaySessionState.Charging].
 *
 * Only what this SDK observes from its own charge. The reader returns one result per read, so there is no
 * member for a prompt raised while a card is presented.
 */
public enum class TapToPayChargeActivity {
    /** Opening the payment with Payabli. No card has been asked for yet. */
    OPENING,

    /** The reader is waiting for a card, and the payer can tap. */
    WAITING_FOR_CARD,

    /** Telling Payabli how the read ended. The card is no longer needed. */
    CLOSING,
}
