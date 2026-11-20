package com.payabli.sdk.payin.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.payabli.sdk.payin.form.PayInFormDraft

/**
 * The drafts the forms at one position last drew, kept as long as the screen holding them.
 *
 * Two forms whose list keys share a hash land on one position, so each form on screen leases its own [Slot].
 */
internal class PayInFormShownDraft : ViewModel() {
    private val slots = mutableListOf<Slot>()

    /**
     * The free slot that last drew [draft], else a free one, else a new one. Forms that left one position cannot be
     * told apart, so when none drew [draft] and several are free, every draft they drew that no form on screen draws
     * is emptied rather than one kept by a guess.
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
                    if (free.size > 1) unclaimed = free.mapNotNull { it.drawn }
                }
        slot.held = true
        return slot
    }

    private fun drawnOnScreen(draft: PayInFormDraft): Boolean = slots.any { it.held && it.drawn === draft }

    /** The draft one form last drew. */
    internal inner class Slot {
        internal var held = false
        private var shown: PayInFormDraft? = null

        /** Drafts left by forms this slot cannot be told apart from, emptied at the next [show]. */
        internal var unclaimed: List<PayInFormDraft> = emptyList()

        internal val drawn: PayInFormDraft? get() = shown

        /**
         * Records that the form now draws [draft], and empties the draft it drew before when that is another.
         *
         * @param draft the draft of the flow the form was handed.
         */
        @JvmSynthetic
        fun show(draft: PayInFormDraft) {
            val left = unclaimed
            unclaimed = emptyList()
            left.filter { it !== draft && !drawnOnScreen(it) }.forEach { it.clearEntries() }
            val previous = shown
            if (previous != null && previous !== draft) previous.clearEntries()
            shown = draft
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

    override fun onForgotten() {
        slot.held = false
    }

    override fun onAbandoned() {
        slot.held = false
    }
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
