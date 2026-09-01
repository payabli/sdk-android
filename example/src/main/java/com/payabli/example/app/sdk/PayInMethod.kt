package com.payabli.example.app.sdk

import com.payabli.sdk.payin.form.PayInMethodType

/**
 * Which instrument the form is on, as this app names it.
 *
 * The form reports a tab change and the setup names the tab it opens on, so the screens have to hold this.
 * The SDK's own enum stays here and maps at those points.
 */
enum class PayInMethod {
    Card,
    BankAccount,
}

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
