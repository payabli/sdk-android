package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInTransactionOptions

/**
 * What to do with what a payer entered.
 *
 * The three operations that read a form. Capturing a transaction authorized earlier carries no instrument, so
 * it is a function on [PayabliPayInPaymentFlow] instead of a case here.
 *
 * Each case carries everything except the instrument, which is what makes a screen configurable before a payer
 * has typed anything.
 */
public sealed class PayabliPayInOperation {
    /** Stores the instrument, so a later transaction charges it without the details again. */
    public class StoreMethod(
        public val options: PayInStoreOptions = PayInStoreOptions(),
    ) : PayabliPayInOperation()

    /** Takes the payment. */
    public class Capture(
        public val options: PayInTransactionOptions,
    ) : PayabliPayInOperation()

    /** Places a hold without taking it, which the service does for entered card data only. */
    public class Authorize(
        public val options: PayInTransactionOptions,
    ) : PayabliPayInOperation()
}
