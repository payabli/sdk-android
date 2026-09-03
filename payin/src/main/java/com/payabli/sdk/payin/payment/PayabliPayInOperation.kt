package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInTransactionOptions

/**
 * What to do with what a payer entered.
 *
 * The three operations that read a form. Capturing a transaction authorized earlier carries no instrument, so
 * it is a function on [com.payabli.sdk.payin.PayabliPayIn] instead of a case here.
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

    /** Places a hold without taking it, which is possible for entered card data only. */
    public class Authorize(
        public val options: PayInTransactionOptions,
    ) : PayabliPayInOperation()

    /**
     * The instruments this operation can carry.
     *
     * An authorization is against entered card data and nothing else, and `MoneyInClient` refuses the rest
     * before sending. A form offering a bank tab for an authorization draws a form a payer can complete and no
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
 * The event this operation is counted under.
 *
 * One name per operation rather than one name carrying the operation, so what is counted comes from a fixed
 * set and an operation added here without a name does not compile.
 */
internal val PayabliPayInOperation.event: String
    get() =
        when (this) {
            is PayabliPayInOperation.StoreMethod -> TelemetryEvents.PAYIN_STORE_METHOD_COMPLETED
            is PayabliPayInOperation.Capture -> TelemetryEvents.PAYIN_CAPTURE_COMPLETED
            is PayabliPayInOperation.Authorize -> TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED
        }

/** The operation as a fixed word, for the form events. Closed, as [event] is. */
internal val PayabliPayInOperation.step: String
    get() =
        when (this) {
            is PayabliPayInOperation.StoreMethod -> "store_method"
            is PayabliPayInOperation.Capture -> "capture"
            is PayabliPayInOperation.Authorize -> "authorize"
        }

/**
 * Everything [offering] reads about an operation, as a `remember` key.
 *
 * A host writing its operation inline hands over a new instance on every recomposition, so the narrowed
 * configuration cannot be keyed on the operation itself. Anything a new branch in [offering] reads belongs
 * here in the same edit, or a form keeps a narrowing made for a different operation: storing a method and
 * capturing offer the same instruments, so the instruments alone do not tell them apart.
 */
internal val PayabliPayInOperation.narrowingKey: List<Any>
    get() = listOf(instruments, this is PayabliPayInOperation.StoreMethod)

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
