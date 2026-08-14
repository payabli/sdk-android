package com.payabli.example.app.sdk

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The prefill has to fill the form the demo actually shows.
 *
 * A field added to a demo section with no value here leaves a gap the QA run fills by hand, which is the whole
 * of what the button is for, and nothing on screen says which one was missed.
 */
class PayInPrefillTest {
    private val setups = mapOf("stored method" to PayInForms.storePaymentMethod(), "capture" to PayInForms.capture())

    @Test
    fun `every field the demo asks a payer to type has a value`() {
        setups.forEach { (screen, setup) ->
            PayInMethodType.entries.forEach { method ->
                val values = PayInPrefill.valuesFor(method)
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
        PayInMethodType.entries.forEach { method ->
            assertEquals(method, PayInPrefill.valuesFor(method).method)
        }
    }

    @Test
    fun `the card expiry is still ahead`() {
        // The one value with a shelf life. The form refuses a month that has passed, so the prefill starts
        // failing validation on its own at some point and the button looks broken.
        val expiry = PayInPrefill.valuesFor(PayInMethodType.Card)[PayInField.CardExpiration]
        val month = YearMonth.parse(expiry, DateTimeFormatter.ofPattern("MM/uu"))

        assertTrue(
            "the prefilled expiry $expiry has passed: give PayInPrefill a later month",
            !month.isBefore(YearMonth.now()),
        )
    }

    /** The fields a payer types into, which is every section that is not read back to them. */
    private fun typedFields(
        setup: PayInFormSetup,
        method: PayInMethodType,
    ): List<PayInField> =
        when (method) {
            PayInMethodType.Card -> setup.configuration.cardSections
            PayInMethodType.BankAccount -> setup.configuration.bankSections
        }.filter { it.style == PayInSectionStyle.Inputs }
            .flatMap { it.fields }
}
