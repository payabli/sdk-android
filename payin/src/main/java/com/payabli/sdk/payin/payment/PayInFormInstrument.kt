package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.client.PayInValidation
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInAccountHolderType
import com.payabli.sdk.payin.model.PayInAccountType
import com.payabli.sdk.payin.model.PayInAchData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInSecCode
import com.payabli.sdk.payin.model.SensitiveDigits

/**
 * Turns what the payer typed into the typed instrument, and overwrites every buffer it made.
 *
 * The values arrive as `String`, which is what a text field holds and what nothing can erase. What these two
 * functions add is that every [SensitiveDigits] built from them is closed before the call returns, however the
 * block ends: a client that throws with the request half sent leaves no readable card number behind.
 *
 * Everything that can refuse the form is read before the first buffer is built, so a refusal leaves nothing to
 * clean up.
 *
 * The instrument lives for the block only. Both functions close its buffers on the way out, so a caller keeping
 * a reference keeps one whose digits read as empty.
 */
internal object PayInFormInstrument {
    /** The card or bank account the values name, for storing a method. */
    suspend fun <T> useInstrument(
        values: PayInFormValues,
        block: suspend (PayInInstrument) -> T,
    ): T {
        val buffers = Buffers()
        try {
            val instrument =
                when (values.method) {
                    PayInMethodType.Card -> PayInInstrument.Card(buffers.card(values))
                    PayInMethodType.BankAccount -> PayInInstrument.BankAccount(buffers.bankAccount(values))
                }
            return block(instrument)
        } finally {
            buffers.close()
        }
    }

    /** The same instrument, as a transaction's payment method. */
    suspend fun <T> usePaymentMethod(
        values: PayInFormValues,
        block: suspend (PayInPaymentMethod) -> T,
    ): T {
        val buffers = Buffers()
        try {
            val method =
                when (values.method) {
                    PayInMethodType.Card -> PayInPaymentMethod.Card(buffers.card(values))
                    PayInMethodType.BankAccount -> PayInPaymentMethod.BankAccount(buffers.bankAccount(values))
                }
            return block(method)
        } finally {
            buffers.close()
        }
    }

    private fun Buffers.card(values: PayInFormValues): PayInCardData {
        // Parsed before the first buffer exists, so an expiry this cannot read has no card number to leave
        // behind.
        val expiry =
            ExpiryValue.parse(values[PayInField.CardExpiration])
                ?: throw PayInException.InvalidInput(
                    PayInValidation.FIELD_CARD_EXPIRY,
                    "The expiry is not a month and a year",
                )
        return PayInCardData(
            cardNumber = of(values[PayInField.CardNumber]),
            expiry = expiry,
            securityCode = of(values[PayInField.CardSecurityCode]),
            holderName = values[PayInField.CardholderName],
            postalCode = values[PayInField.CardPostalCode],
        )
    }

    private fun Buffers.bankAccount(values: PayInFormValues): PayInAchData {
        // The three choices first, as the card's expiry is: each can refuse the form, and a refusal before the
        // account number is buffered leaves nothing to clean up.
        val accountType = accountType(values[PayInField.AccountType])
        val holderType = holderType(values[PayInField.AccountHolderType])
        val secCode = secCode(values[PayInField.SecCode])
        return PayInAchData(
            accountNumber = of(values[PayInField.AccountNumber]),
            routingNumber = values[PayInField.RoutingNumber],
            accountType = accountType,
            holderName = values[PayInField.AccountHolder],
            holderType = holderType,
            secCode = secCode,
            deviceId = values[PayInField.DeviceId].trim().takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Checking where the form does not ask, which is the kind a payer entering a routing and account number
     * has. The service reads this field on every bank request, so there is no absent value to send.
     */
    private fun accountType(value: String): PayInAccountType =
        chosen(
            value = value,
            options = PayInAccountType.entries,
            wireName = { it.wireName },
            field = PayInValidation.FIELD_ACH_ACCOUNT_TYPE,
            subject = "An account type",
        ) ?: PayInAccountType.Checking

    /** Absent where the form does not ask, which leaves the member out of the body for the paypoint to decide. */
    private fun holderType(value: String): PayInAccountHolderType? =
        chosen(
            value = value,
            options = PayInAccountHolderType.entries,
            wireName = { it.wireName },
            field = PayInValidation.FIELD_ACH_HOLDER_TYPE,
            subject = "A holder type",
        )

    /** Absent leaves the body writer to send `WEB`, which is what the service assumes. */
    private fun secCode(value: String): PayInSecCode? =
        chosen(
            value = value,
            options = PayInSecCode.entries,
            wireName = { it.wireName },
            field = PayInValidation.FIELD_ACH_SEC_CODE,
            subject = "An authorization code",
        )

    /**
     * The option a payer chose, matched to its enum by wire name.
     *
     * Case-insensitive, because the form's own option values and the enums differ in it: the form offers `web`
     * where [PayInSecCode.Web] is `WEB`.
     *
     * A value matching nothing is refused, so a configuration offering an option this SDK does not know reaches
     * the caller as that field being wrong.
     */
    private fun <T : Enum<T>> chosen(
        value: String,
        options: List<T>,
        wireName: (T) -> String,
        field: String,
        subject: String,
    ): T? {
        val choice = value.trim()
        if (choice.isEmpty()) return null
        return options.firstOrNull { wireName(it).equals(choice, ignoreCase = true) }
            ?: throw PayInException.InvalidInput(field, "$subject this SDK does not offer was chosen")
    }

    /**
     * The buffers built for one instrument, closed together.
     *
     * A list, so closing does not have to know which instrument was built or which of its fields are buffered.
     */
    private class Buffers {
        private val opened = mutableListOf<SensitiveDigits>()

        fun of(value: String): SensitiveDigits = SensitiveDigits.ofString(value).also { opened += it }

        fun close() {
            opened.forEach { it.close() }
        }
    }
}
