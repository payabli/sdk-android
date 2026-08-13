package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.payin.client.PayInRoutes
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.model.PayInException

/**
 * Which form field a refusal was about.
 *
 * Two refusals name a field, in the same spelling: this module's own, which carries the service's name for the
 * value it would not send, and a validation 400, whose `errors` map is keyed by the parameter the service
 * refused. Both are translated here, so a refusal lands on the field that caused it.
 *
 * A decline, a network failure and a service error name no field and produce nothing.
 */
internal object PayInRefusedFields {
    /** What the failure blamed, per field, or empty when it blamed nothing this form draws. */
    fun of(failure: Throwable): Map<PayInField, PayInFieldError> =
        when (failure) {
            is PayInException.InvalidInput ->
                listOfNotNull(failure.field?.let(::fieldFor)).associateWith { PayInFieldError.NotAccepted }

            is PayabliValidationException ->
                failure.fieldErrors.keys
                    .mapNotNull(::fieldFor)
                    .associateWith { PayInFieldError.NotAccepted }

            else -> emptyMap()
        }

    /**
     * The field a wire name refers to, or null when the form has no box for it.
     *
     * The last segment, because the same field is named both bare and under its parent object: this module
     * refuses `paymentMethod.cardnumber` while ASP.NET model validation reports the property on its own. Lower
     * cased, because the service's casing for a request field and for the error naming it differ.
     */
    private fun fieldFor(name: String): PayInField? = FIELDS[name.substringAfterLast('.').lowercase()]

    /**
     * The wire spellings [PayInRoutes] already holds, lower cased.
     *
     * `entryPoint` and the names a stored method is charged by are absent: a payer types none of them, so a
     * refusal naming one has no box to mark and reaches the caller on the exception alone.
     */
    private val FIELDS: Map<String, PayInField> =
        mapOf(
            PayInRoutes.FIELD_CARD_NUMBER to PayInField.CardNumber,
            PayInRoutes.FIELD_CARD_EXPIRY to PayInField.CardExpiration,
            PayInRoutes.FIELD_CARD_SECURITY_CODE to PayInField.CardSecurityCode,
            PayInRoutes.FIELD_CARD_HOLDER to PayInField.CardholderName,
            PayInRoutes.FIELD_CARD_POSTAL_CODE to PayInField.CardPostalCode,
            PayInRoutes.FIELD_ACH_ACCOUNT to PayInField.AccountNumber,
            PayInRoutes.FIELD_ACH_ACCOUNT_TYPE to PayInField.AccountType,
            PayInRoutes.FIELD_ACH_ROUTING to PayInField.RoutingNumber,
            PayInRoutes.FIELD_ACH_HOLDER to PayInField.AccountHolder,
            PayInRoutes.FIELD_ACH_HOLDER_TYPE to PayInField.AccountHolderType,
            PayInRoutes.FIELD_ACH_SEC_CODE to PayInField.SecCode,
            PayInRoutes.FIELD_DEVICE to PayInField.DeviceId,
            PayInRoutes.FIELD_TOTAL_AMOUNT to PayInField.Amount,
            PayInRoutes.FIELD_SERVICE_FEE to PayInField.ServiceFee,
            // The customer half. The form collects these and the request carries them, so a refusal naming
            // one has a box to mark.
            PayInRoutes.FIELD_CUSTOMER_FIRST_NAME to PayInField.FirstName,
            PayInRoutes.FIELD_CUSTOMER_LAST_NAME to PayInField.LastName,
            PayInRoutes.FIELD_CUSTOMER_NUMBER to PayInField.CustomerNumber,
            PayInRoutes.FIELD_CUSTOMER_BILLING_EMAIL to PayInField.BillingEmail,
            PayInRoutes.FIELD_CUSTOMER_BILLING_ZIP to PayInField.BillingPostalCode,
            PayInRoutes.FIELD_METHOD_DESCRIPTION to PayInField.MethodDescription,
        ).mapKeys { it.key.lowercase() }
}
