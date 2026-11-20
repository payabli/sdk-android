package com.payabli.sdk.payin.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.payabli.sdk.core.model.PayabliErrorCategory
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * What this was last started from, so re-entering a composition does not empty it.
     *
     * Read and replaced in one step, because [clear] can run on another thread between the two.
     */
    private val seededFrom = AtomicReference<PayInFormConfiguration?>(null)

    private val entered = mutableStateMapOf<PayInField, String>()

    /** Every value the payer has typed, for the instrument on screen. */
    val typed: Map<PayInField, String> get() = entered

    /**
     * The fields the service objected to on the last submission, dropped one at a time as the payer edits.
     *
     * A marked box whose value has changed no longer holds what was rejected.
     */
    var rejectedFields: Map<PayInField, PayInFieldError> by mutableStateOf(emptyMap())

    /**
     * The last submission's failure where no box on screen was marked for it, or where its outcome is unknown, or
     * null for none.
     * Cleared when the next submission starts, when one succeeds, and with the rest of the draft.
     */
    var failureNotice: PayInFailureNotice? by mutableStateOf(null)

    /**
     * True from the tap until an outcome arrives, which is how a success this form sent is told from one the
     * host was already holding.
     */
    var submissionPending: Boolean by mutableStateOf(false)

    /**
     * True when the last submission sent saved a payment method. Taken at the tap, because a host can change the
     * operation before the answer arrives, and the notice describes the submission that was sent.
     */
    var sentSavesMethod: Boolean by mutableStateOf(false)

    /**
     * Opens [method] on [configuration], starts the draft the first time, and applies a different configuration
     * without starting it again, keeping the values and the marks the new one still has a box for. The comparison
     * stays here: a `remember` key is gone with its composition, and a write the same composition reads never settles.
     *
     * @param configuration the configuration this composition draws the form from.
     * @param method the form's method.
     */
    fun seed(
        configuration: PayInFormConfiguration,
        method: PayInFormMethod,
    ) {
        method.open(configuration)
        val previous = seededFrom.getAndSet(configuration)
        if (previous == configuration) {
            // A form that comes back on another method, as one with nowhere to keep it does, drops what it hides.
            keepOnlyWhatHasABox(configuration, method.current)
            return
        }

        if (previous != null) {
            keepOnlyWhatHasABox(configuration, method.current)
            rejectedFields = configuration.rejectedFieldsOnScreen(rejectedFields, method.current)
        } else {
            entered.clear()
            rejectedFields = emptyMap()
        }
    }

    /**
     * A keystroke. The box no longer holds what the service rejected, so its mark goes.
     *
     * @param field the field typed into.
     * @param value what the payer typed.
     */
    fun enter(
        field: PayInField,
        value: String,
    ) {
        entered[field] = value
        rejectedFields = rejectedFields - field
    }

    /**
     * The payer switched instrument, dropping what the new one has no box for. Ignored while a submission is
     * pending: the selector is disabled only from the next frame, and the method on screen is the one sent.
     *
     * @param chosen the instrument switched to.
     * @param configuration the configuration the new instrument's form comes from.
     * @param method the form's method, which moves to [chosen].
     */
    fun switchTo(
        chosen: PayInMethodType,
        configuration: PayInFormConfiguration,
        method: PayInFormMethod,
    ) {
        if (submissionPending) return
        method.switchTo(chosen)
        // A card number typed under the card tab is not sent with a bank payment, and is not kept out of sight
        // either.
        keepOnlyWhatHasABox(configuration, chosen)
        rejectedFields = configuration.rejectedFieldsOnScreen(rejectedFields, chosen)
    }

    private fun keepOnlyWhatHasABox(
        configuration: PayInFormConfiguration,
        method: PayInMethodType,
    ) {
        entered.keys.retainAll(configuration.inputFieldsFor(method).toSet())
    }

    /** The instrument goes once the service may have acted on a submission, approved or refused. */
    fun clearInstrument() = PayInSensitiveFields.CLEARED_ON_OUTCOME.forEach { entered.remove(it) }

    /** Everything the payer entered goes, and the next composition starts the draft again. */
    fun clear() {
        clearEntries()
        submissionPending = false
    }

    /**
     * Everything the payer entered goes, as with [clear], and a submission in flight still delivers its outcome
     * when this draft is drawn again.
     */
    fun clearEntries() {
        seededFrom.set(null)
        entered.clear()
        rejectedFields = emptyMap()
        failureNotice = null
    }
}

/**
 * A failure the form shows a message for, and whether the submission it describes saved a method.
 *
 * @property category the failure's catalog category, which picks the message.
 * @property savesMethod true when the failed submission saved a payment method rather than paying.
 */
internal data class PayInFailureNotice(
    val category: PayabliErrorCategory,
    val savesMethod: Boolean,
) {
    /**
     * The category to draw on a form doing [savesMethod]'s operation. A message about a payment is not drawn on a
     * save form, or the reverse: it would describe a submission that form never sent.
     *
     * @param savesMethod true when the form on screen saves a payment method.
     * @return [category], or null when the form's operation is not the one that failed.
     */
    fun categoryOn(savesMethod: Boolean): PayabliErrorCategory? = category.takeIf { this.savesMethod == savesMethod }
}
