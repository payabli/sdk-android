package com.payabli.sdk.payin.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.payabli.sdk.payin.PayInPaymentFlow
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormMethod
import com.payabli.sdk.payin.form.PayInFormSubmitter

/**
 * The drafts the forms at one position last drew, kept as long as the screen holding them.
 *
 * Two forms whose list keys share a hash land on one position, so each form on screen leases its own [Slot].
 */
internal class PayInFormShownDraft : ViewModel() {
    private val slots = mutableListOf<Slot>()

    /**
     * The free slot that last drew [draft], else a free one, else a new one. Forms sharing a position cannot be told
     * apart, so a slot reused there is handed none of the outcomes it awaited, and with several free, every draft left
     * that no form on screen draws is emptied rather than one kept by a guess.
     *
     * @param draft the draft of the flow the form enters with.
     * @return the slot, now held.
     */
    @JvmSynthetic
    fun lease(draft: PayInFormDraft): Slot {
        val free = slots.filter { !it.held }
        val slot =
            free.firstOrNull { it.drawn === draft }
                ?: (free.firstOrNull() ?: Slot().also { slots += it }).apply {
                    if (free.size > 1) unclaimed = free.mapNotNull { it.shown }
                }
        slot.held = true
        slot.guessed = slots.size > 1
        return slot
    }

    /** A draft a form drew, with the lease of its flow, which says whether any form draws it now. */
    internal class Drawn(
        val draft: PayInFormDraft,
        val lease: PayInFormLease,
    )

    /** The draft one form last drew. */
    internal inner class Slot : PayInFormSubmitter {
        internal var held = false
        internal var shown: Drawn? = null
            private set

        override val onScreen: Boolean get() = held

        /** Drafts left by forms this slot cannot be told apart from, emptied at the next [show]. */
        internal var unclaimed: List<Drawn> = emptyList()

        /** Whether the slot was taken where forms share the position, so it gives up its submissions at [show]. */
        internal var guessed = false

        /** Gives the slot back, with nothing left to empty. */
        @JvmSynthetic
        fun release() {
            held = false
            unclaimed = emptyList()
            guessed = false
        }

        internal val drawn: PayInFormDraft? get() = shown?.draft

        /** The method this form shows, kept across the flows it is handed. */
        internal val method = PayInFormMethod()

        /** The flows this form submitted to whose outcome may not have reached the host yet. */
        internal val awaited = mutableStateListOf<PayInPaymentFlow>()

        /**
         * Records that the form now draws [draft], and empties the draft it drew before, and any [unclaimed], that no
         * form draws, at this position or any other. A [guessed] slot also gives up the submissions it made, whose
         * outcome then goes to the form that draws their flow.
         *
         * @param draft the draft of the flow the form was handed.
         * @param lease the lease of that flow.
         */
        @JvmSynthetic
        fun show(
            draft: PayInFormDraft,
            lease: PayInFormLease,
        ) {
            val left = unclaimed + listOfNotNull(shown)
            unclaimed = emptyList()
            shown = Drawn(draft, lease)
            left.filter { it.lease.holder == null }.forEach { it.draft.clearEntries() }
            if (guessed) {
                guessed = false
                awaited.forEach { if (it.draft.submitter === this) it.draft.submitter = null }
                awaited.clear()
            }
        }
    }
}

/** Holds a slot while the form is in the composition, and gives it back when the form leaves or is abandoned. */
private class SlotLease(
    holder: PayInFormShownDraft,
    draft: PayInFormDraft,
) : RememberObserver {
    val slot = holder.lease(draft)

    override fun onRemembered() = Unit

    override fun onForgotten() = slot.release()

    override fun onAbandoned() = slot.release()
}

/**
 * The slot for the form at this point of the composition. It outlives a configuration change and the form leaving
 * the composition, so a swap made while the form is off screen still empties the previous draft. Without a
 * `ViewModelStoreOwner` it lives only as long as the composition.
 *
 * @param draft the draft of the flow the form is drawn with.
 * @return this form's slot.
 */
@Composable
internal fun rememberPayInFormShownDraft(draft: PayInFormDraft): PayInFormShownDraft.Slot {
    // Keyed on the position, so forms at different positions never meet. A position repeats in every composition
    // root, so the root's view id is in the key too, which is the id Compose saves state under.
    val view = LocalView.current
    val root = (view.parent as? View)?.id ?: view.id
    val key = "payabli.payin.form:$root:$currentCompositeKeyHashCode"
    val owner = LocalViewModelStoreOwner.current
    val holder =
        if (owner ==
            null
        ) {
            remember { PayInFormShownDraft() }
        } else {
            viewModel(owner, key = key) { PayInFormShownDraft() }
        }
    return remember(holder) { SlotLease(holder, draft) }.slot
}
