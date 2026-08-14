package com.payabli.example.app.sdk

import com.payabli.example.app.demo.qa.QaIdentity
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInSectionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The prefill has to fill the form the demo actually shows.
 *
 * A field added to a demo section with no value here leaves a gap the QA run fills by hand, which is the whole
 * of what the button is for, and nothing on screen says which one was missed.
 */
class PayInPrefillTest {
    private val identity = QaIdentity.from("Google Pixel 7a")

    private val setups =
        mapOf(
            "stored method" to PayInForms.storePaymentMethod(),
            "capture" to PayInForms.capture(BigDecimal("1.10")),
        )

    @Test
    fun `every field the demo asks a payer to type has a value`() {
        setups.forEach { (screen, setup) ->
            PayInMethod.entries.forEach { method ->
                val values = PayInPrefill.valuesFor(method, identity).values
                typedFields(setup, method).forEach { field ->
                    assertTrue(
                        "$screen asks for $field as $method and the prefill leaves it empty",
                        values[field].isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    fun `the prefill carries the method it was asked for`() {
        PayInMethod.entries.forEach { method ->
            assertEquals(
                method,
                PayInPrefill
                    .valuesFor(method, identity)
                    .values.method
                    .asMethod(),
            )
        }
    }

    @Test
    fun `the card expiry is still ahead`() {
        // The one value with a shelf life. The form refuses a month that has passed, so the prefill starts
        // failing validation on its own at some point and the button looks broken.
        val expiry = PayInPrefill.valuesFor(PayInMethod.Card, identity).values[PayInField.CardExpiration]
        val month = YearMonth.parse(expiry, DateTimeFormatter.ofPattern("MM/uu"))

        assertTrue(
            "the prefilled expiry $expiry has passed: give PayInPrefill a later month",
            !month.isBefore(YearMonth.now()),
        )
    }

    @Test
    fun `every field naming the payer names this device`() {
        // The whole point of the identity: three phones and a simulator submitting at once produce rows a
        // dashboard can attribute. A field that kept a constant is a row that cannot be told from the others.
        PayInMethod.entries.forEach { method ->
            val values = PayInPrefill.valuesFor(method, identity).values
            val holder = if (method == PayInMethod.Card) PayInField.CardholderName else PayInField.AccountHolder

            assertEquals(identity.holderName, values[holder])
            assertEquals(identity.lastName, values[PayInField.LastName])
            assertEquals(identity.customerNumber, values[PayInField.CustomerNumber])
            assertEquals(identity.billingEmail, values[PayInField.BillingEmail])
        }
    }

    /** The fields a payer types into, which is every section that is not read back to them. */
    private fun typedFields(
        setup: PayInFormSetup,
        method: PayInMethod,
    ): List<PayInField> =
        when (method) {
            PayInMethod.Card -> setup.configuration.cardSections
            PayInMethod.BankAccount -> setup.configuration.bankSections
        }.filter { it.style == PayInSectionStyle.Inputs }
            .flatMap { it.fields }
}
