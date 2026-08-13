package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInMethodType
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

    /**
     * The instruments this operation can carry.
     *
     * The service authorizes entered card data and nothing else, and `MoneyInClient` refuses the rest before
     * sending. A form offering a bank tab for an authorization draws a form a payer can complete and no
     * request can be made from, so the form reads this and offers what is left.
     */
    internal val instruments: Set<PayInMethodType>
        get() =
            when (this) {
                is Authorize -> setOf(PayInMethodType.Card)
                is Capture, is StoreMethod -> PayInMethodType.entries.toSet()
            }
}

/**
 * [configuration] with any instrument this operation cannot carry left out.
 *
 * A pairing that leaves nothing is refused rather than drawn. An authorization with a bank-only form is a form
 * every tap refuses locally, and each refusal now empties the account the payer entered, so it is a form nobody
 * can submit and everybody can fill in. `PayInFormConfiguration` refuses an unsubmittable form where its
 * sections are written; this is the same refusal one layer later, where the operation joins them.
 */
internal fun PayabliPayInOperation.offering(configuration: PayInFormConfiguration): PayInFormConfiguration {
    val offered = configuration.methodsOffered.filter { it in instruments }
    require(offered.isNotEmpty()) {
        "$this cannot carry ${configuration.methodsOffered.joinToString()}: it takes ${instruments.joinToString()}"
    }

    // Only a stored method has a description. On any other operation the box is filled in and dropped.
    if (this !is PayabliPayInOperation.StoreMethod) {
        val described = offered.filter { PayInField.MethodDescription in configuration.inputFieldsFor(it) }
        require(described.isEmpty()) {
            "$this cannot carry ${PayInField.MethodDescription}: only storing a method does"
        }
    }
    if (offered == configuration.methodsOffered) return configuration
    return configuration.copy(
        allowedMethods = offered,
        defaultMethod = configuration.defaultMethod.takeIf { it in offered } ?: offered.first(),
    )
}
