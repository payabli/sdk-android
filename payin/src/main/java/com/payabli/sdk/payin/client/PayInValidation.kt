package com.payabli.sdk.payin.client

import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.form.PayInAmountRefusal
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFieldRules
import com.payabli.sdk.payin.form.amountRefusal
import com.payabli.sdk.payin.model.ExpiryValue
import com.payabli.sdk.payin.model.PayInBankAccountData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInValidationOptions

/**
 * What this module refuses before it builds a request. A bound [PayInFieldRules] or [amountRefusal] carries is read
 * from there and never restated, since two copies of a bound is how the form and the client disagree about one
 * field. Every refusal names the field in its wire spelling and carries no submitted value.
 */
internal object PayInValidation {
    /** The wire limit, and the one bound not expressible as a field rule. */
    private const val NAME_MAX = 60

    /** Letters, digits, spaces and the three punctuation marks a bank will accept in a name. */
    private val HOLDER_NAME = Regex("^[A-Za-z0-9 .'-]+$")

    fun entryPoint(value: String) {
        if (value.isBlank()) throw PayInException.InvalidInput(FIELD_ENTRY_POINT, "An entry point is required")
    }

    fun instrument(
        instrument: PayInInstrument,
        options: PayInValidationOptions,
    ) {
        when (instrument) {
            is PayInInstrument.Card -> card(instrument.data, options)
            is PayInInstrument.BankAccount -> bankAccount(instrument.data, options)
        }
    }

    fun paymentMethod(
        method: PayInPaymentMethod,
        options: PayInValidationOptions,
    ) {
        when (method) {
            is PayInPaymentMethod.Card -> card(method.data, options)
            is PayInPaymentMethod.BankAccount -> bankAccount(method.data, options)
            is PayInPaymentMethod.Stored ->
                required(method.storedMethodId, "paymentMethod.storedMethodId", "A stored method id is required")

            is PayInPaymentMethod.CloudDevice ->
                required(method.deviceId, "paymentMethod.device", "A cloud device is required")

            is PayInPaymentMethod.Check ->
                required(method.holderName, "paymentMethod.checkHolder", "A check holder name is required")

            PayInPaymentMethod.Cash -> Unit
        }
    }

    /**
     * The amounts, refused as [amountRefusal] decides, which is also what empties the form's summary.
     *
     * @param details what is being charged.
     */
    fun paymentDetails(details: PayInPaymentDetails) {
        val (field, message) =
            when (details.amountRefusal() ?: return) {
                PayInAmountRefusal.TotalOutOfRange -> FIELD_TOTAL_AMOUNT to "The total amount is out of range"
                PayInAmountRefusal.TotalNotMoreThanZero ->
                    FIELD_TOTAL_AMOUNT to "The total amount must be more than zero"
                PayInAmountRefusal.ServiceFeeOutOfRange -> FIELD_SERVICE_FEE to "The service fee is out of range"
                PayInAmountRefusal.ServiceFeeNegative -> FIELD_SERVICE_FEE to "A service fee cannot be negative"
                PayInAmountRefusal.SurchargeOutOfRange -> FIELD_SURCHARGE_FEE to "The surcharge is out of range"
                PayInAmountRefusal.SurchargeNegative -> FIELD_SURCHARGE_FEE to "A surcharge cannot be negative"
                PayInAmountRefusal.FeesReachTotal ->
                    FIELD_TOTAL_AMOUNT to "The service fee and surcharge must be less than the total amount"
            }
        throw PayInException.InvalidInput(field, message)
    }

    fun transId(value: String) {
        if (value.isBlank()) throw PayInException.InvalidInput("transId", "A transaction id is required")
        // A dot segment is unreserved, so encoding leaves it intact and it would still address a different
        // path. No transaction identifier looks like this.
        if (value.trim() == "." || value.trim() == "..") {
            throw PayInException.InvalidInput("transId", "A transaction id is required")
        }
    }

    /**
     * The card, read through [SensitiveDigits.useDigits] so each copy is overwritten before this returns.
     *
     * Every check below can throw, which is why the scoped read is the one used here: a copy handed out by
     * `read` and abandoned mid-validation is a card number left in a buffer nothing can reach.
     *
     * @param data the card to check.
     * @param options which structural checks run.
     */
    private fun card(
        data: PayInCardData,
        options: PayInValidationOptions,
    ) {
        data.cardNumber.useDigits { number ->
            if (number.isMissing()) {
                throw PayInException.InvalidInput(FIELD_CARD_NUMBER, "A card number is required")
            }
            // The switch is applied to the answer rather than passed into the rules: a caller turning the check
            // off is saying it has its own opinion of the check digit, not that the length no longer matters.
            val error = PayInFieldRules.error(PayInField.CardNumber, number)
            val checkDigitWaived = error == PayInFieldError.CardNumberNotValid && !options.checksCardNumber
            if (error != null && !checkDigitWaived) {
                throw PayInException.InvalidInput(FIELD_CARD_NUMBER, "The card number is not valid")
            }
        }

        data.securityCode.useDigits { securityCode ->
            if (securityCode.isMissing()) {
                throw PayInException.InvalidInput(FIELD_CARD_CVV, "A security code is required")
            }
            PayInFieldRules
                .error(PayInField.CardSecurityCode, securityCode)
                ?.let { throw PayInException.InvalidInput(FIELD_CARD_CVV, "The security code is not valid") }
        }

        // The form refuses a past expiry, and so would sending it, one round trip later.
        val today = ExpiryValue.today()
        if (data.expiry.isExpired(today.year, today.month)) {
            throw PayInException.InvalidInput(FIELD_CARD_EXPIRY, "The card has expired")
        }

        name(data.holderName, FIELD_CARD_HOLDER, "A cardholder name is required")
        required(data.postalCode, FIELD_CARD_ZIP, "A postal code is required")
        // Trimmed, because that is what the writer sends: judging the untrimmed value refuses a code that
        // would have gone out inside the limit.
        PayInFieldRules
            .error(PayInField.CardPostalCode, data.postalCode.trim().toCharArray())
            ?.let { throw PayInException.InvalidInput(FIELD_CARD_ZIP, "The postal code is too long") }
    }

    private fun bankAccount(
        data: PayInBankAccountData,
        options: PayInValidationOptions,
    ) {
        data.accountNumber.useDigits { account ->
            if (account.isMissing()) {
                throw PayInException.InvalidInput(FIELD_ACH_ACCOUNT, "An account number is required")
            }
            PayInFieldRules
                .error(PayInField.AccountNumber, account)
                ?.let { throw PayInException.InvalidInput(FIELD_ACH_ACCOUNT, "The account number is not valid") }
        }

        required(data.routingNumber, FIELD_ACH_ROUTING, "A routing number is required")
        val routingError = PayInFieldRules.error(PayInField.RoutingNumber, data.routingNumber.trim().toCharArray())
        val checksumWaived = routingError == PayInFieldError.RoutingNumberNotValid && !options.checksRoutingNumber
        if (routingError != null && !checksumWaived) {
            throw PayInException.InvalidInput(FIELD_ACH_ROUTING, "The routing number is not valid")
        }

        name(data.holderName, FIELD_ACH_HOLDER, "An account holder name is required")
        // Only the bank name is character-restricted: a bank rejects what it cannot print on a statement.
        if (!HOLDER_NAME.matches(data.holderName.trim())) {
            throw PayInException.InvalidInput(
                FIELD_ACH_HOLDER,
                "An account holder name takes letters, digits, spaces, and the characters . ' -",
            )
        }
    }

    /**
     * Blank counts as absent, which the field rules cannot say for a buffer: `PayInFieldRules.error`
     * answers null for a blank value, so a buffer holding only spaces passes every rule and reaches the
     * body writer, whose digit check raises an internal defect instead of the typed missing-field error.
     *
     * @return true when the buffer holds nothing but whitespace; false otherwise.
     */
    private fun CharArray.isMissing(): Boolean = isEmpty() || all { it.isWhitespace() }

    private fun name(
        value: String,
        field: String,
        missing: String,
    ) {
        required(value, field, missing)
        if (value.trim().length > NAME_MAX) {
            throw PayInException.InvalidInput(field, "A name is at most $NAME_MAX characters")
        }
    }

    private fun required(
        value: String,
        field: String,
        missing: String,
    ) {
        if (value.isBlank()) throw PayInException.InvalidInput(field, missing)
    }

    /**
     * The names a refusal carries, built from the wire spellings [PayInRoutes] already holds.
     *
     * A refusal from this module and one from the wire then name the same field, and the spelling has one
     * home. `paymentMethod.` is the path a nested field is reported under.
     *
     * @param field the field's wire spelling.
     * @return the name a refusal carries, under `paymentMethod.`.
     */
    private fun inPaymentMethod(field: String): String = "${PayInRoutes.FIELD_PAYMENT_METHOD}.$field"

    private fun inPaymentDetails(field: String): String = "${PayInRoutes.FIELD_PAYMENT_DETAILS}.$field"

    internal val FIELD_ENTRY_POINT: String = PayInRoutes.FIELD_ENTRY_POINT
    internal val FIELD_TOTAL_AMOUNT: String = inPaymentDetails(PayInRoutes.FIELD_TOTAL_AMOUNT)
    internal val FIELD_SERVICE_FEE: String = inPaymentDetails(PayInRoutes.FIELD_SERVICE_FEE)
    internal val FIELD_SURCHARGE_FEE: String = inPaymentDetails(PayInRoutes.FIELD_SURCHARGE_FEE)
    private val AMOUNT_FIELDS: Set<String> = setOf(FIELD_TOTAL_AMOUNT, FIELD_SERVICE_FEE, FIELD_SURCHARGE_FEE)

    /**
     * Whether [cause] is an amount refused before anything was sent, which leaves every field as the payer typed it.
     *
     * @param cause what a submission failed with.
     * @return true when [cause] is an amount refusal; false otherwise.
     */
    fun refusedAnAmount(cause: PayabliException): Boolean =
        cause is PayInException.InvalidInput && cause.field in AMOUNT_FIELDS

    internal val FIELD_CARD_NUMBER: String = inPaymentMethod(PayInRoutes.FIELD_CARD_NUMBER)
    internal val FIELD_CARD_CVV: String = inPaymentMethod(PayInRoutes.FIELD_CARD_SECURITY_CODE)
    internal val FIELD_CARD_EXPIRY: String = inPaymentMethod(PayInRoutes.FIELD_CARD_EXPIRY)
    internal val FIELD_CARD_HOLDER: String = inPaymentMethod(PayInRoutes.FIELD_CARD_HOLDER)
    internal val FIELD_CARD_ZIP: String = inPaymentMethod(PayInRoutes.FIELD_CARD_POSTAL_CODE)
    internal val FIELD_ACH_ACCOUNT: String = inPaymentMethod(PayInRoutes.FIELD_ACH_ACCOUNT)
    internal val FIELD_ACH_ACCOUNT_TYPE: String = inPaymentMethod(PayInRoutes.FIELD_ACH_ACCOUNT_TYPE)
    internal val FIELD_ACH_ROUTING: String = inPaymentMethod(PayInRoutes.FIELD_ACH_ROUTING)
    internal val FIELD_ACH_HOLDER: String = inPaymentMethod(PayInRoutes.FIELD_ACH_HOLDER)
    internal val FIELD_ACH_HOLDER_TYPE: String = inPaymentMethod(PayInRoutes.FIELD_ACH_HOLDER_TYPE)
    internal val FIELD_ACH_SEC_CODE: String = inPaymentMethod(PayInRoutes.FIELD_ACH_SEC_CODE)
    internal val FIELD_DEVICE: String = inPaymentMethod(PayInRoutes.FIELD_DEVICE)
}
