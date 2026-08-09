package com.payabli.sdk.payin.ui

import androidx.compose.runtime.Immutable
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle

/** What every part of the form reads, and what none of it changes. */
@Immutable
internal data class PayInFormContext(
    val configuration: PayInFormConfiguration,
    val labels: PayInFormLabels,
    val style: PayInFormStyle,
    val today: ExpiryValue,
    val enabled: Boolean,
)
