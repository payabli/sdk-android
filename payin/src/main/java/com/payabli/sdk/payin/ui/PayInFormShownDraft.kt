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
     * Takes the first slot no form on screen holds, or a new one.
     *
     * @return the slot, now held.
     */
    @JvmSynthetic
    fun lease(): Slot = (slots.firstOrNull { !it.held } ?: Slot().also { slots += it }).apply { held = true }

    /** The draft one form last drew. */
    internal class Slot {
        internal var held = false
        private var shown: PayInFormDraft? = null

        /**
         * Records that the form now draws [draft], and empties the draft it drew before when that is another.
         *
         * @param draft the draft of the flow the form was handed.
         */
        @JvmSynthetic
        fun show(draft: PayInFormDraft) {
            val previous = shown
            if (previous != null && previous !== draft) previous.clearEntries()
            shown = draft
        }
    }
}

/** Holds a slot while the form is in the composition, and gives it back when the form leaves or is abandoned. */
private class SlotLease(
    holder: PayInFormShownDraft,
) : RememberObserver {
    val slot = holder.lease()

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
 * @return this form's slot.
 */
@Composable
internal fun rememberPayInFormShownDraft(): PayInFormShownDraft.Slot {
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
    return remember(holder) { SlotLease(holder) }.slot
}
