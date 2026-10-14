package com.payabli.sdk.payin.model

/**
 * What became of a transaction.
 *
 * A settled status will not change. [Initiated], [Authorized], [Failed] and [Other] are not settled and may change,
 * so read the transaction again before acting on one of them.
 */
public sealed class PayInTransactionStatus {
    /** Opened and not yet charged. Not settled. */
    public data object Initiated : PayInTransactionStatus()

    /** Held, and waiting to be captured or released. Not settled. */
    public data object Authorized : PayInTransactionStatus()

    /** Charged. Settled. */
    public data object Captured : PayInTransactionStatus()

    /** Refused by the processor. Settled. */
    public data object Declined : PayInTransactionStatus()

    /** Reversed before it settled. Settled. */
    public data object Voided : PayInTransactionStatus()

    /** Charged, then returned. Settled. */
    public data object Returned : PayInTransactionStatus()

    /** Failed, or not yet answered. Not settled. */
    public data object Failed : PayInTransactionStatus()

    /**
     * A status this SDK does not name. Not settled.
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
         * Maps a wire status number by name, never by position, so a number this SDK does not know is [Other].
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
                -2 -> Returned
                -1, 0 -> Failed
                else -> Other(raw)
            }
    }
}
