package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fields a success empties, pinned as a list.
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
    fun `the instrument is what a success empties`() {
        assertEquals(
            setOf(
                PayInField.CardNumber,
                PayInField.CardExpiration,
                PayInField.CardSecurityCode,
                PayInField.RoutingNumber,
                PayInField.AccountNumber,
            ),
            PayInSensitiveFields.CLEARED_ON_SUCCESS,
        )
    }

    @Test
    fun `every field is on one side or the other`() {
        assertEquals(
            "a field was added to the form and neither cleared nor kept",
            kept,
            PayInField.entries.toSet() - PayInSensitiveFields.CLEARED_ON_SUCCESS,
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
            secret.filterNot { it in PayInSensitiveFields.CLEARED_ON_SUCCESS },
        )
    }
}
