package com.payabli.example.app.sdk

import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.form.PayInSectionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The prefill has to fill the form the demo actually shows.
 *
 * A field added to a demo section with no value here leaves a gap the demo run fills by hand, which is the whole
 * of what the button is for, and nothing on screen says which one was missed.
 */
class PayInPrefillTest {
    private val identity = SampleIdentity.from("Google Pixel 7a")

    private val values = PayInPrefill.valuesFor(identity)

    private val setups =
        mapOf(
            "stored method" to PayInForms.storePaymentMethod(),
            "capture" to PayInForms.capture(BigDecimal("1.10")),
        )

    @Test
    fun `every field the demo asks a payer to type has a value`() {
        setups.forEach { (screen, setup) ->
            PayInMethodType.entries.forEach { method ->
                typedFields(setup, method).filterNot { it in PICKED_BY_HAND }.forEach { field ->
                    assertTrue(
                        "$screen asks for $field as $method and the prefill leaves it empty",
                        values[field].orEmpty().isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    fun `the two controls a payer picks from carry no value`() {
        // Not an omission. Neither is a text box, and a box is the only thing the prefill can write to, so a
        // value here would be one the button silently fails to apply.
        PICKED_BY_HAND.forEach { field ->
            assertTrue("$field is picked rather than typed and the prefill carries it", field !in values)
        }
    }

    @Test
    fun `every field naming the payer names this device`() {
        // The whole point of the identity: three phones and a simulator submitting at once produce rows a
        // dashboard can attribute. A field that kept a constant is a row that cannot be told from the others.
        listOf(PayInField.CardholderName, PayInField.AccountHolder).forEach { holder ->
            assertEquals(identity.holderName, values[holder])
        }
        assertEquals(identity.lastName, values[PayInField.LastName])
        assertEquals(identity.customerNumber, values[PayInField.CustomerNumber])
        assertEquals(identity.billingEmail, values[PayInField.BillingEmail])
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

    private companion object {
        /** The expiry's dialog and the account type's menu, neither of which takes text. */
        val PICKED_BY_HAND = setOf(PayInField.CardExpiration, PayInField.AccountType)
    }
}
