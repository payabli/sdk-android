package com.payabli.sdk.payin.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInMethodType

/**
 * Where every word on the form comes from. A caller's [PayInFormLabels] wins, then the resource.
 */
internal object PayInStrings {
    @Composable
    @ReadOnlyComposable
    fun label(
        field: PayInField,
        labels: PayInFormLabels,
    ): String = labels.labelFor(field) ?: stringResource(field.labelResource)

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

/** The resource carrying this field's default label. */
@get:StringRes
internal val PayInField.labelResource: Int
    get() =
        when (this) {
            PayInField.CardholderName -> R.string.payabli_payin_field_cardholder_name
            PayInField.CardNumber -> R.string.payabli_payin_field_card_number
            PayInField.CardExpiration -> R.string.payabli_payin_field_card_expiration
            PayInField.CardSecurityCode -> R.string.payabli_payin_field_card_security_code
            PayInField.CardPostalCode -> R.string.payabli_payin_field_card_postal_code
            PayInField.AccountHolder -> R.string.payabli_payin_field_account_holder
            PayInField.RoutingNumber -> R.string.payabli_payin_field_routing_number
            PayInField.AccountNumber -> R.string.payabli_payin_field_account_number
            PayInField.AccountType -> R.string.payabli_payin_field_account_type
            PayInField.AccountHolderType -> R.string.payabli_payin_field_account_holder_type
            PayInField.SecCode -> R.string.payabli_payin_field_sec_code
            PayInField.DeviceId -> R.string.payabli_payin_field_device_id
            PayInField.MethodDescription -> R.string.payabli_payin_field_method_description
            PayInField.FirstName -> R.string.payabli_payin_field_first_name
            PayInField.LastName -> R.string.payabli_payin_field_last_name
            PayInField.CustomerNumber -> R.string.payabli_payin_field_customer_number
            PayInField.BillingEmail -> R.string.payabli_payin_field_billing_email
            PayInField.BillingPostalCode -> R.string.payabli_payin_field_billing_postal_code
            PayInField.Amount -> R.string.payabli_payin_field_amount
            PayInField.ServiceFee -> R.string.payabli_payin_field_service_fee
        }
