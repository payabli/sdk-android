package com.payabli.sdk.payin.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.payabli.sdk.core.model.PayabliErrorCategory
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.model.PayInException

/**
 * The form's message for a failed submission, chosen by the failure's category and never by its text, which is
 * the developer's.
 */
internal object PayInFailureMessage {
    /**
     * The category whose message the form draws for a failure. A payment whose outcome is unknown keeps its
     * cause's category, so the wrapper is checked first: the payment may have gone through, whatever went wrong.
     *
     * @param cause the failure the submission ended with.
     * @return the category that picks the message.
     */
    fun categoryOf(cause: PayabliException): PayabliErrorCategory =
        if (cause is PayInException.Unsettled) PayabliErrorCategory.OUTCOME_UNKNOWN else cause.category

    /**
     * The SDK's own text for a category. A save moves no money, so its texts never say a payment was declined,
     * and a save whose answer was lost says only that it is unconfirmed.
     *
     * @param category the category the message is for.
     * @param savesMethod true when the form saves a payment method rather than paying.
     * @return the string resource for that text.
     */
    @StringRes
    fun defaultText(
        category: PayabliErrorCategory,
        savesMethod: Boolean,
    ): Int =
        if (savesMethod) {
            when (category) {
                PayabliErrorCategory.DECLINED -> R.string.payabli_payin_failure_save_declined
                PayabliErrorCategory.RETRY_LATER -> R.string.payabli_payin_failure_save_retry_later
                PayabliErrorCategory.OUTCOME_UNKNOWN -> R.string.payabli_payin_failure_save_outcome_unknown
                else -> R.string.payabli_payin_failure_save_other
            }
        } else {
            when (category) {
                PayabliErrorCategory.DECLINED -> R.string.payabli_payin_failure_payment_declined
                PayabliErrorCategory.RETRY_LATER -> R.string.payabli_payin_failure_payment_retry_later
                PayabliErrorCategory.OUTCOME_UNKNOWN -> R.string.payabli_payin_failure_payment_outcome_unknown
                else -> R.string.payabli_payin_failure_payment_other
            }
        }

    /**
     * The host's text for a category, or null to use the SDK's own.
     *
     * @param category the category the message is for.
     * @param labels the form's wording.
     * @return the host's text, or null where it gave none or a blank one.
     */
    fun hostText(
        category: PayabliErrorCategory,
        labels: PayInFormLabels,
    ): String? =
        when (category) {
            PayabliErrorCategory.DECLINED -> labels.declinedMessageOrNull()
            PayabliErrorCategory.RETRY_LATER -> labels.retryLaterMessageOrNull()
            PayabliErrorCategory.OUTCOME_UNKNOWN -> labels.outcomeUnknownMessageOrNull()
            else -> labels.otherMessageOrNull()
        }

    /**
     * The message the payer reads for a category.
     *
     * @param category the category the message is for.
     * @param savesMethod true when the form saves a payment method rather than paying.
     * @param labels the form's wording.
     * @return the host's text where it gave one, otherwise the SDK's.
     */
    @Composable
    @ReadOnlyComposable
    fun text(
        category: PayabliErrorCategory,
        savesMethod: Boolean,
        labels: PayInFormLabels,
    ): String = hostText(category, labels) ?: stringResource(defaultText(category, savesMethod))
}
