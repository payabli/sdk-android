package com.payabli.sdk.payin.ui

import androidx.compose.runtime.Immutable
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle

/**
 * What every part of the form reads, and what none of it changes.
 *
 * [refreshClock] is the one way out: a field that is about to offer a choice of months asks for
 * [today] to be read again first, so an idle form does not offer one that has gone.
 *
 * [refused] is what the last submission's refusal named. The rules answer for the value in the box;
 * this answers for the value that was sent.
 */
@Immutable
internal data class PayInFormContext(
    val configuration: PayInFormConfiguration,
    val labels: PayInFormLabels,
    val style: PayInFormStyle,
    val today: ExpiryValue,
    val enabled: Boolean,
    val refused: Map<PayInField, PayInFieldError>,
    val refreshClock: () -> Unit,
)
