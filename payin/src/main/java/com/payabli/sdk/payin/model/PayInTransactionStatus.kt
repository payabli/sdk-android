package com.payabli.sdk.payin.model

/**
 * What became of a transaction.
 *
 * Each status says whether its outcome is known. Where it is not, the status may still change, so read the
 * transaction again before acting on it.
 */
public sealed class PayInTransactionStatus {
    /** Opened and not yet charged. Outcome not known. Wire status 10. */
    public data object Initiated : PayInTransactionStatus()

    /** Held, and waiting to be captured or released. Outcome not known. Wire status 11. */
    public data object Authorized : PayInTransactionStatus()

    /** Paid. Outcome known, and it can still be voided until its batch closes. Wire status 1. */
    public data object Captured : PayInTransactionStatus()

    /** Not paid: the card or account was declined. Outcome known. Wire statuses 2 and 3. */
    public data object Declined : PayInTransactionStatus()

    /** Not paid: reversed by a void. Outcome known. Wire status 5. */
    public data object Voided : PayInTransactionStatus()

    /** Not paid: refused before it was charged, so no money moved. Outcome known. Wire status -2. */
    public data object Blocked : PayInTransactionStatus()

    /** Failed, or not yet answered. Outcome not known. Wire statuses -1 and 0. */
    public data object Failed : PayInTransactionStatus()

    /**
     * A status this SDK does not name: any other wire status. Outcome not known.
     *
     * @property raw the status number the service sent.
     */
    public class Other(
        public val raw: Int,
    ) : PayInTransactionStatus() {
        override fun equals(other: Any?): Boolean = other is Other && other.raw == raw

        override fun hashCode(): Int = raw

        override fun toString(): String = "Other(raw=$raw)"
    }

    internal companion object {
        /**
         * Maps a wire status number to its status, and a number this SDK does not name to [Other].
         *
         * @param raw the status number the service sent.
         * @return the status it names.
         */
        @JvmSynthetic
        fun of(raw: Int): PayInTransactionStatus =
            when (raw) {
                10 -> Initiated
                11 -> Authorized
                1 -> Captured
                2, 3 -> Declined
                5 -> Voided
                -2 -> Blocked
                -1, 0 -> Failed
                else -> Other(raw)
            }
    }
}
