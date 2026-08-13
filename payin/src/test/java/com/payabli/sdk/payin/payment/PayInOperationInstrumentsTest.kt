package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun `a bank-only form paired with an authorization is refused`() {
        // Drawn, it is a form every tap refuses locally and each refusal empties the account just entered.
        val bankOnly =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.BankAccount),
                defaultMethod = PayInMethodType.BankAccount,
                cardSections = listOf(PayInFormSection(fields = listOf(PayInField.CardNumber))),
            )

        val refusal = runCatching { authorize().offering(bankOnly) }.exceptionOrNull()

        assertTrue("a form nothing could submit was drawn", refusal is IllegalArgumentException)
        assertTrue(
            "does not name the instrument: ${refusal?.message}",
            refusal?.message?.contains("BankAccount") == true,
        )
    }

    @Test
    fun `only a stored method can ask for a method description`() {
        // Every other operation sends the typed text nowhere.
        val described =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card),
                cardSections =
                    PayInFormConfiguration.defaultCardSections() +
                        PayInFormSection(fields = listOf(PayInField.MethodDescription)),
            )

        assertSame(described, PayabliPayInOperation.StoreMethod().offering(described))
        listOf(capture(), authorize()).forEach { operation ->
            val refusal = runCatching { operation.offering(described) }.exceptionOrNull()
            assertTrue("$operation drew a box it would drop", refusal is IllegalArgumentException)
        }
    }

    @Test
    fun `two authorizations narrow to configurations that compare equal`() {
        // The form keys the values a payer has typed on the configuration, and `remember` compares its keys with
        // equals. A host building its operation inline hands over a new instance on every recomposition, so the
        // narrowed configurations have to be equal or the boxes empty as the payer types.
        val first = authorize().offering(bothMethods)
        val second = authorize().offering(bothMethods)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    private fun authorize() = PayabliPayInOperation.Authorize(testOptions())

    private fun capture() = PayabliPayInOperation.Capture(testOptions())
}
