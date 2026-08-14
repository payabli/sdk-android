package com.payabli.sdk.payin.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What a payer has entered, held outside the composition that draws it.
 *
 * A rotation, a fold, a switch to another tab and a return from a pushed screen all end the form's composition,
 * so state kept in `remember` goes with it and the payer types the card again. This is held by
 * `PayabliPayInPaymentFlow`, which a host keeps for the life of the screen.
 *
 * **Nothing here reaches saved instance state.** A `Bundle` is serialized by the system and can be written to
 * disk, and what recovers a submission interrupted by process death is the idempotency key rather than a copy of
 * the card. A form reopened after process death is an empty form.
 */
internal class PayInFormDraft {
    /**
     * What this was last filled from, so re-entering a composition does not refill it.
     *
     * The seed is held as its hash and not as the object. A caller's [PayInFormValues] can carry a card number,
     * and keeping one here would hold the caller's copy for the life of the screen, past the point where an
     * outcome empties the boxes drawn from it. The configuration is held as itself, carrying no payer input.
     *
     * The cost is that two different seeds sharing a hash are read as one, and the form keeps the values it
     * already has.
     */
    private var seededFrom: Pair<PayInFormConfiguration, Int?>? = null

    private val entered = mutableStateMapOf<PayInField, String>()

    private var chosen: PayInMethodType? by mutableStateOf(null)

    /**
     * The instrument on screen.
     *
     * Read rather than captured, so the submit button's second check at the tap sees what the payer chose
     * rather than what the last composition drew.
     */
    val method: PayInMethodType
        get() = checkNotNull(chosen) { "a form draft was read before it was seeded" }

    /** Every value the payer has typed, for the instrument on screen. */
    val typed: Map<PayInField, String> get() = entered

    /**
     * The fields the service objected to on the last submission, dropped one at a time as the payer edits.
     *
     * A marked box whose value has changed no longer holds what was rejected.
     */
    var rejectedFields: Map<PayInField, PayInFieldError> by mutableStateOf(emptyMap())

    /**
     * True from the tap until an outcome arrives, which is how a success this form sent is told from one the
     * host was already holding.
     */
    var submissionPending: Boolean by mutableStateOf(false)

    /**
     * Fills from [initialValues] the first time, and again whenever the caller hands over a different
     * configuration or a different set of values.
     *
     * Called on every composition and compares what it was last filled from, so a form that leaves the
     * composition and comes back keeps what the payer typed. Both types compare by value, so a caller rebuilding
     * an equal configuration after a rotation is not handing over a new one.
     *
     * The comparison has to be here rather than at the call site: a `remember` key belongs to a composition and
     * is gone with it, and refilling on every composition writes state that the same composition then reads, so
     * the form recomposes without ever settling.
     *
     * A seeded instrument the configuration does not offer is ignored, as a seeded value with no box is.
     */
    fun seed(
        configuration: PayInFormConfiguration,
        initialValues: PayInFormValues?,
    ) {
        val key = configuration to initialValues?.hashCode()
        if (seededFrom == key) return
        seededFrom = key

        chosen = initialValues?.method?.takeIf { it in configuration.methodsOffered } ?: configuration.startingMethod
        entered.clear()
        initialValues?.values?.forEach { (field, value) -> if (value.isNotEmpty()) entered[field] = value }
        rejectedFields = emptyMap()
    }

    /** A keystroke. The box no longer holds what the service rejected, so its mark goes. */
    fun enter(
        field: PayInField,
        value: String,
    ) {
        entered[field] = value
        rejectedFields = rejectedFields - field
    }

    /** The payer switched instrument, dropping what the new one has no box for. */
    fun switchTo(
        method: PayInMethodType,
        configuration: PayInFormConfiguration,
    ) {
        chosen = method
        // A card number typed under the card tab is not sent with a bank payment, and is not kept out of sight
        // either.
        entered.keys.retainAll(configuration.inputFieldsFor(method).toSet())
        rejectedFields = configuration.rejectedFieldsOnScreen(rejectedFields, method)
    }

    /** The instrument goes once the submission has an outcome, approved or refused. */
    fun clearInstrument() = PayInSensitiveFields.CLEARED_ON_OUTCOME.forEach { entered.remove(it) }

    /**
     * Everything goes, and the next composition fills from the caller's values again.
     *
     * Called when the host's scope is cancelled, which is the screen going for good.
     */
    fun clear() {
        seededFrom = null
        chosen = null
        entered.clear()
        rejectedFields = emptyMap()
        submissionPending = false
    }
}
