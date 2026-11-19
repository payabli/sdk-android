package com.payabli.sdk.payin.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.payabli.sdk.payin.form.PayInFormDraft

/**
 * The draft one form last drew, kept as long as the screen holding the form.
 */
internal class PayInFormShownDraft : ViewModel() {
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

/**
 * The holder for the form at this point of the composition. It outlives a configuration change and the form leaving
 * the composition, so a swap made while the form is off screen still empties the previous draft. Without a
 * `ViewModelStoreOwner` it lives only as long as the composition.
 *
 * @return this form's holder.
 */
@Composable
internal fun rememberPayInFormShownDraft(): PayInFormShownDraft {
    // Keyed on the position, so two forms on one screen do not empty each other's drafts. A position repeats in
    // every composition root, so the root's view id is in the key too, which is the id Compose saves state under.
    val view = LocalView.current
    val root = (view.parent as? View)?.id ?: view.id
    val key = "payabli.payin.form:$root:$currentCompositeKeyHashCode"
    val owner = LocalViewModelStoreOwner.current ?: return remember { PayInFormShownDraft() }
    return viewModel(owner, key = key) { PayInFormShownDraft() }
}
