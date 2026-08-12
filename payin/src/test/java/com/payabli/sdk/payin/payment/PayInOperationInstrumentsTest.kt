package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A form offers the instruments its operation can carry.
 *
 * The service authorizes entered card data only, so a bank tab beside an authorization is a tab a payer can
 * complete and no request can be made from: the refusal arrives on the tap, naming a restriction the form never
 * showed. Storing a method and capturing take either instrument.
 */
class PayInOperationInstrumentsTest {
    private val bothMethods =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.BankAccount,
        )

    @Test
    fun `an authorization drops the bank tab`() {
        val offered = authorize().offering(bothMethods)

        assertEquals(listOf(PayInMethodType.Card), offered.methodsOffered)
    }

    @Test
    fun `an authorization moves a bank default onto the tab that is left`() {
        // The default was the dropped one, and a form starting on a tab it does not offer has no fields.
        assertEquals(PayInMethodType.Card, authorize().offering(bothMethods).startingMethod)
    }

    @Test
    fun `a capture and a stored method offer both`() {
        listOf(capture(), PayabliPayInOperation.StoreMethod()).forEach { operation ->
            assertSame(
                "$operation narrowed a form it can submit either way",
                bothMethods,
                operation.offering(bothMethods),
            )
        }
    }

    @Test
    fun `a card-only form is handed back as it stands`() {
        val cardOnly = PayInFormConfiguration(allowedMethods = listOf(PayInMethodType.Card))

        assertSame(cardOnly, authorize().offering(cardOnly))
    }

    @Test
    fun `a bank-only form is left alone, because there is no card form to fall back to`() {
        // Dropping the one offered instrument would leave card sections this configuration was never checked
        // for: only offered instruments are checked when one is built. The tap refuses with the reason.
        val bankOnly =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.BankAccount),
                defaultMethod = PayInMethodType.BankAccount,
                cardSections = listOf(PayInFormSection(fields = listOf(PayInField.CardNumber))),
            )

        assertSame(bankOnly, authorize().offering(bankOnly))
    }

    private fun authorize() = PayabliPayInOperation.Authorize(testOptions())

    private fun capture() = PayabliPayInOperation.Capture(testOptions())
}
