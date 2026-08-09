package com.payabli.sdk.payin.ui

import androidx.compose.runtime.Immutable
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle

/**
 * The five things every part of the form needs and none of them changes on its own.
 *
 * They travelled as five parameters through four levels of composable, which is most of what each
 * signature was.
 */
@Immutable
internal data class PayInFormContext(
    val configuration: PayInFormConfiguration,
    val labels: PayInFormLabels,
    val style: PayInFormStyle,
    val today: ExpiryValue,
    val enabled: Boolean,
)
