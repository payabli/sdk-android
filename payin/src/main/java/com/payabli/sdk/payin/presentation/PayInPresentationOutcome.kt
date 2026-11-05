package com.payabli.sdk.payin.presentation

/** How a presentation ended, as the call waiting on it receives it. */
internal sealed interface PayInPresentationOutcome<out R> {
    /**
     * The contract answered with [value]. Not a `data class`: the value can be a wallet's payment data, and a
     * synthesized `toString` would put it into any assertion failure or crash report.
     */
    class Completed<out R>(
        val value: R,
    ) : PayInPresentationOutcome<R>

    /** The person backed out, or the Activity finished before the contract answered. */
    data object Cancelled : PayInPresentationOutcome<Nothing>
}
