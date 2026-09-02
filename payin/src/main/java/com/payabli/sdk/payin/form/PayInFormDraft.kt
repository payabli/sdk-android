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
 * `PayabliPayIn`, which a host keeps for the life of the screen.
 *
 * **Nothing here reaches saved instance state.** A `Bundle` is serialized by the system and can be written to
 * disk, and what recovers a submission interrupted by process death is the idempotency key rather than a copy of
 * the card. A form reopened after process death is an empty form.
 */
internal class PayInFormDraft {
    /** What this was last started from, so re-entering a composition does not empty it. */
    @Volatile
    private var seededFrom: PayInFormConfiguration? = null

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
     * Starts the form the first time, and again whenever the caller hands over a different configuration.
     *
     * Called on every composition and compares what it was last started from, so a form that leaves the
     * composition and comes back keeps what the payer typed. [PayInFormConfiguration] compares by value, so a
     * caller rebuilding an equal one after a rotation is not handing over a new one.
     *
     * The comparison has to be here rather than at the call site: a `remember` key belongs to a composition and
     * is gone with it, and emptying on every composition writes state that the same composition then reads, so
     * the form recomposes without ever settling.
     */
    fun seed(configuration: PayInFormConfiguration) {
        if (seededFrom == configuration) return
        seededFrom = configuration

        chosen = configuration.startingMethod
        entered.clear()
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
     * Everything the payer entered goes, and the next composition starts the form again.
     *
     * Called when the host's scope is cancelled, which runs on whatever thread completed it while a composition
     * may be reading. [chosen] is left alone for that reason: a reader that has already passed [seed]'s check
     * goes on to read [method], and clearing the instrument under it fails that read. Which tab was on screen
     * is not the payer's data, and the next [seed] sets it before anything reads it.
     */
    fun clear() {
        seededFrom = null
        entered.clear()
        rejectedFields = emptyMap()
        submissionPending = false
    }
}
