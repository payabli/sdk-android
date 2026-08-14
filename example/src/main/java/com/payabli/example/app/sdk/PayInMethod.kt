package com.payabli.example.app.sdk

import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType

/**
 * Which instrument the form is on, as this app names it.
 *
 * The form reports a tab change and the prefill button fills the tab on screen, so the two screens have to
 * hold this. The SDK's own enum stays here and maps at those two points.
 */
enum class PayInMethod {
    Card,
    BankAccount,
}

/** Values to start the form from, kept opaque so a screen holds no SDK type to seed it with. */
class PayInFormSeed internal constructor(
    internal val values: PayInFormValues,
)

internal fun PayInMethodType.asMethod(): PayInMethod =
    when (this) {
        PayInMethodType.Card -> PayInMethod.Card
        PayInMethodType.BankAccount -> PayInMethod.BankAccount
    }

internal fun PayInMethod.asMethodType(): PayInMethodType =
    when (this) {
        PayInMethod.Card -> PayInMethodType.Card
        PayInMethod.BankAccount -> PayInMethodType.BankAccount
    }
