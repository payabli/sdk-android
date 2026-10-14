package com.payabli.sdk.payin.model

/**
 * Where a transaction stands, read from [PayInTransaction.transStatus], which keeps the number.
 *
 * Each case names the status numbers it covers and whether the payment's outcome is known. Where it is not, the
 * status may still change, so read the transaction again before acting on it.
 */
public sealed class PayInTransactionStatus {
    /** 10. Outcome not known. */
    public data object Initiated : PayInTransactionStatus()

    /** 11. Outcome not known. */
    public data object Authorized : PayInTransactionStatus()

    /** 1. Outcome known: paid, and still voidable until its batch closes. */
    public data object Captured : PayInTransactionStatus()

    /** 2 and 3. Outcome known: not paid. */
    public data object Declined : PayInTransactionStatus()

    /** 5. Outcome known: not paid. */
    public data object Voided : PayInTransactionStatus()

    /** -2. Outcome known: not paid, and no money moved. */
    public data object Blocked : PayInTransactionStatus()

    /** -1 and 0. Outcome not known. */
    public data object Failed : PayInTransactionStatus()

    /**
     * Any other number, carried here. Outcome not known.
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
