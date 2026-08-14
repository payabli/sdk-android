package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fields an outcome empties, pinned as a list.
 *
 * Both halves are written out, so a field added to [PayInField] fails here until somebody has decided whether a
 * payer should have to type it again. Asserting only the cleared half passes on a new card field being kept.
 */
class PayInSensitiveFieldsTest {
    private val kept =
        setOf(
            PayInField.CardholderName,
            PayInField.CardPostalCode,
            PayInField.AccountHolder,
            PayInField.AccountType,
            PayInField.AccountHolderType,
            PayInField.SecCode,
            PayInField.DeviceId,
            PayInField.MethodDescription,
            PayInField.FirstName,
            PayInField.LastName,
            PayInField.CustomerNumber,
            PayInField.BillingEmail,
            PayInField.BillingPostalCode,
            PayInField.Amount,
            PayInField.ServiceFee,
        )

    @Test
    fun `the instrument is what an outcome empties`() {
        assertEquals(
            setOf(
                PayInField.CardNumber,
                PayInField.CardExpiration,
                PayInField.CardSecurityCode,
                PayInField.RoutingNumber,
                PayInField.AccountNumber,
            ),
            PayInSensitiveFields.CLEARED_ON_OUTCOME,
        )
    }

    @Test
    fun `every field is on one side or the other`() {
        assertEquals(
            "a field was added to the form and neither cleared nor kept",
            kept,
            PayInField.entries.toSet() - PayInSensitiveFields.CLEARED_ON_OUTCOME,
        )
    }

    @Test
    fun `nothing obscured as it is typed is kept`() {
        // A field the form masks is one the payer cannot read back, so keeping it leaves a value nobody can
        // check standing in a box after the payment it belonged to.
        val secret = PayInField.entries.filter { it.input == PayInFieldInput.Secret }
        assertTrue("no masked field to check", secret.isNotEmpty())
        assertEquals(
            emptyList<PayInField>(),
            secret.filterNot { it in PayInSensitiveFields.CLEARED_ON_OUTCOME },
        )
    }

    @Test
    fun `a rejected field the other instrument also draws stays rejected after the switch`() {
        // Switching tab keeps that box and the value in it, so dropping the rejection would let the same value go
        // out again without the edit the gate asks for.
        val rejected =
            mapOf(
                PayInField.FirstName to PayInFieldError.NotAccepted,
                PayInField.CardNumber to PayInFieldError.CardNumberNotValid,
            )

        val standing = twoMethods.rejectedFieldsOnScreen(rejected, PayInMethodType.BankAccount)

        assertEquals(mapOf(PayInField.FirstName to PayInFieldError.NotAccepted), standing)
    }

    @Test
    fun `a rejection naming a field the chosen instrument does not draw is dropped`() {
        // It would gate a form with no box to correct: the card number is not on screen behind the bank tab.
        val rejected = mapOf(PayInField.CardNumber to PayInFieldError.CardNumberNotValid)

        assertEquals(
            emptyMap<PayInField, PayInFieldError>(),
            twoMethods.rejectedFieldsOnScreen(rejected, PayInMethodType.BankAccount),
        )
        assertEquals(rejected, twoMethods.rejectedFieldsOnScreen(rejected, PayInMethodType.Card))
    }

    /** Both instruments, each drawing its own field and the payer's name. */
    private val twoMethods =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.Card,
            cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS + PayInField.FirstName)),
            bankSections = listOf(PayInFormSection(fields = BANK_INSTRUMENT_FIELDS + PayInField.FirstName)),
        )
}
