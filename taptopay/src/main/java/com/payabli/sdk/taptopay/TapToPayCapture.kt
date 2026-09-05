package com.payabli.sdk.taptopay

/**
 * Whether the card was charged, for a payment that did not complete.
 *
 * Three values rather than two, because the middle one is reachable and is the dangerous one to report as
 * either neighbour. Read it before offering a retry: only [NOT_CHARGED] means charging again cannot take the
 * money twice.
 */
public enum class TapToPayCapture {
    /** The card was never asked for, so no money moved. */
    NOT_CHARGED,

    /**
     * The card was asked for and what happened to it is unknown.
     *
     * The processor takes the sale before the reader hands the answer back, so a tap that ends without one
     * may still have moved money. Reconcile the payment rather than retrying it.
     */
    UNKNOWN,

    /**
     * The card was charged and the payment was left open.
     *
     * The money has moved, so charging again takes it a second time. Finish this payment with
     * [PayabliTTP.closeCapturedCharge] instead, which needs no second tap.
     */
    CHARGED,
}
