package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.model.PayInTransactionStatus

/** What reading a transaction back found, in this app's own words. */
sealed class PayInReadOutcome {
    /**
     * The transaction was read.
     *
     * @property status the status as a screen shows it, saying whether the outcome is known.
     * @property statusNumber the raw status number from the service, or null when it sent none.
     * @property paymentTransactionId the transaction that was read.
     * @property isVoided whether the service reports the transaction voided.
     */
    class Read internal constructor(
        val status: String,
        val statusNumber: Int?,
        val paymentTransactionId: String?,
        val isVoided: Boolean = false,
    ) : PayInReadOutcome() {
        /**
         * Leaves out the transaction ID.
         *
         * @return the status alone.
         */
        override fun toString(): String = "PayInReadOutcome.Read(status=$status)"
    }

    /**
     * The read failed.
     *
     * @property error the failure as a screen shows it.
     */
    class Failed internal constructor(
        val error: PaymentError,
    ) : PayInReadOutcome()
}

/**
 * Converts the SDK's read result into this app's type.
 *
 * @return what the read found, or why it failed.
 */
internal fun Result<PayInTransaction>.toReadOutcome(): PayInReadOutcome =
    fold(
        onSuccess = {
            PayInReadOutcome.Read(
                status = it.status?.label() ?: "not reported",
                statusNumber = it.transStatus,
                paymentTransactionId = it.paymentTransId,
                isVoided = it.status == PayInTransactionStatus.Voided,
            )
        },
        onFailure = { PayInReadOutcome.Failed(it.toPaymentError()) },
    )

/**
 * Names the status for display.
 *
 * @return the status name, saying whether the outcome is known.
 */
private fun PayInTransactionStatus.label(): String =
    when (this) {
        PayInTransactionStatus.Initiated -> "initiated, outcome not known"
        PayInTransactionStatus.Authorized -> "authorized, outcome not known"
        PayInTransactionStatus.Captured -> "captured"
        PayInTransactionStatus.Declined -> "declined"
        PayInTransactionStatus.Voided -> "voided"
        PayInTransactionStatus.Blocked -> "blocked"
        PayInTransactionStatus.Failed -> "failed, outcome not known"
        is PayInTransactionStatus.Other -> "unrecognised, outcome not known"
    }
