package com.payabli.sdk.payin.form

import androidx.annotation.StringRes
import com.payabli.sdk.payin.R

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
            PayInField.SurchargeFee -> R.string.payabli_payin_field_surcharge_fee
        }
