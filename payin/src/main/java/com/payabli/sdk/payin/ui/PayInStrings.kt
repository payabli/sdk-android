package com.payabli.sdk.payin.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSummaryRows

/**
 * Where every word on the form comes from. A caller's [PayInFormLabels] wins, then the resource.
 */
internal object PayInStrings {
    @Composable
    @ReadOnlyComposable
    fun label(
        field: PayInField,
        labels: PayInFormLabels,
    ): String = PayInSummaryRows.labelText(field, labels, resources())

    @Composable
    @ReadOnlyComposable
    fun total(labels: PayInFormLabels): String = PayInSummaryRows.totalLabelText(labels, resources())

    @Composable
    @ReadOnlyComposable
    fun placeholder(
        field: PayInField,
        labels: PayInFormLabels,
    ): String? = labels.placeholderFor(field)

    @Composable
    @ReadOnlyComposable
    fun method(method: PayInMethodType): String =
        stringResource(
            when (method) {
                PayInMethodType.Card -> R.string.payabli_payin_method_card
                PayInMethodType.BankAccount -> R.string.payabli_payin_method_bank_account
            },
        )

    /** The message for a rule's finding, with the numbers that rule decided. */
    @Composable
    @ReadOnlyComposable
    fun error(error: PayInFieldError): String =
        when (error) {
            PayInFieldError.DigitsOnly -> stringResource(R.string.payabli_payin_error_digits_only)
            is PayInFieldError.ShorterThan ->
                pluralStringResource(R.plurals.payabli_payin_error_shorter_than, error.minimum, error.minimum)

            is PayInFieldError.LongerThan ->
                pluralStringResource(R.plurals.payabli_payin_error_longer_than, error.maximum, error.maximum)

            is PayInFieldError.TooManyCharacters ->
                pluralStringResource(
                    R.plurals.payabli_payin_error_too_many_characters,
                    error.maximum,
                    error.maximum,
                )

            is PayInFieldError.NotExactly ->
                pluralStringResource(R.plurals.payabli_payin_error_not_exactly, error.length, error.length)

            is PayInFieldError.OutsideRange ->
                pluralStringResource(
                    R.plurals.payabli_payin_error_outside_range,
                    error.maximum,
                    error.minimum,
                    error.maximum,
                )

            PayInFieldError.CardNumberNotValid -> stringResource(R.string.payabli_payin_error_card_number)
            PayInFieldError.RoutingNumberNotValid -> stringResource(R.string.payabli_payin_error_routing_number)
            PayInFieldError.EmailNotValid -> stringResource(R.string.payabli_payin_error_email)
            PayInFieldError.ExpiryIncomplete -> stringResource(R.string.payabli_payin_error_expiry_incomplete)
            PayInFieldError.ExpiryPast -> stringResource(R.string.payabli_payin_error_expiry_past)
            PayInFieldError.NotAccepted -> stringResource(R.string.payabli_payin_error_not_accepted)
        }

    /** The context resources are read through, recomposing when the configuration changes as `stringResource` does. */
    @Composable
    @ReadOnlyComposable
    private fun resources(): Context {
        LocalConfiguration.current
        return LocalContext.current
    }

    /** The options a choice field offers, as the API's values paired with what a payer reads. */
    @Composable
    @ReadOnlyComposable
    fun choices(field: PayInField): List<Pair<String, String>> =
        when (field) {
            PayInField.AccountType ->
                listOf(
                    "Checking" to stringResource(R.string.payabli_payin_account_type_checking),
                    "Savings" to stringResource(R.string.payabli_payin_account_type_savings),
                )

            PayInField.AccountHolderType ->
                listOf(
                    "personal" to stringResource(R.string.payabli_payin_holder_type_personal),
                    "business" to stringResource(R.string.payabli_payin_holder_type_business),
                )

            PayInField.SecCode ->
                listOf(
                    "web" to stringResource(R.string.payabli_payin_sec_code_web),
                    "ppd" to stringResource(R.string.payabli_payin_sec_code_ppd),
                    "ccd" to stringResource(R.string.payabli_payin_sec_code_ccd),
                    "tel" to stringResource(R.string.payabli_payin_sec_code_tel),
                )
            else -> emptyList()
        }
}
