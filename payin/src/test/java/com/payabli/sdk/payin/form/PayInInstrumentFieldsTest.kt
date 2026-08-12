package com.payabli.sdk.payin.form

import com.payabli.sdk.payin.client.PayInValidation
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInValidationOptions
import com.payabli.sdk.payin.payment.PayInFormInstrument
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A form that offers a method has to collect what that method's instrument is built from.
 *
 * Omitted, the field is not on screen and cannot be filled, so the form completes, Submit enables, and every
 * submission is refused naming the service's spelling for a box the payer never saw. The configuration refuses
 * it instead, where the sections were written.
 *
 * The last test is what keeps the set honest: each field in it is refused by the client when it arrives blank,
 * so a field that stops being required stops being demanded of the form in the same change.
 */
class PayInInstrumentFieldsTest {
    @Test
    fun `a card form missing the expiry is refused where the sections are written`() {
        val refusal =
            refusedConfiguration(
                PayInMethodType.Card,
                CARD_INSTRUMENT_FIELDS - PayInField.CardExpiration,
            )

        assertTrue("a card form with no expiry was accepted", refusal is IllegalArgumentException)
        assertTrue("does not name the field: ${refusal?.message}", refusal?.message?.contains("CardExpiration") == true)
    }

    @Test
    fun `a bank form missing the routing number is refused too`() {
        val refusal =
            refusedConfiguration(
                PayInMethodType.BankAccount,
                BANK_INSTRUMENT_FIELDS - PayInField.RoutingNumber,
            )

        assertTrue("a bank form with no routing number was accepted", refusal is IllegalArgumentException)
    }

    @Test
    fun `a method that is not offered is not asked for its fields`() {
        // Card-only, with bank sections that collect almost nothing: what the form never draws cannot fail.
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card),
            bankSections = listOf(PayInFormSection(fields = listOf(PayInField.AccountHolder))),
        )
    }

    @Test
    fun `a summary section does not count as collecting the field`() {
        // Read back rather than typed into, so a payer cannot supply it.
        val refusal =
            runCatching {
                PayInFormConfiguration(
                    allowedMethods = listOf(PayInMethodType.Card),
                    cardSections =
                        listOf(
                            PayInFormSection(fields = CARD_INSTRUMENT_FIELDS - PayInField.CardPostalCode),
                            PayInFormSection(
                                fields = listOf(PayInField.CardPostalCode),
                                style = PayInSectionStyle.Summary,
                            ),
                        ),
                )
            }.exceptionOrNull()

        assertTrue("a summary field was taken as collected", refusal is IllegalArgumentException)
    }

    @Test
    fun `an amount a payer could type into is refused`() {
        // The amounts belong to the operation and nothing reads them back out of the form, so a box for one
        // would show a figure the request does not carry: the screen says $5 and the service takes $1.10.
        listOf(PayInField.Amount, PayInField.ServiceFee).forEach { field ->
            val refusal =
                runCatching {
                    PayInFormConfiguration(
                        allowedMethods = listOf(PayInMethodType.Card),
                        cardSections = listOf(PayInFormSection(fields = CARD_INSTRUMENT_FIELDS + field)),
                    )
                }.exceptionOrNull()

            assertTrue("$field was accepted as a box to type into", refusal is IllegalArgumentException)
            assertTrue("does not name the field: ${refusal?.message}", refusal?.message?.contains("$field") == true)
        }
    }

    @Test
    fun `an amount read back in a summary section is what a caller does instead`() {
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card),
            cardSections =
                listOf(
                    PayInFormSection(fields = CARD_INSTRUMENT_FIELDS),
                    PayInFormSection(
                        fields = listOf(PayInField.Amount, PayInField.ServiceFee),
                        style = PayInSectionStyle.Summary,
                    ),
                ),
            summaryValues = mapOf(PayInField.Amount to "$ 1.10"),
        )
    }

    @Test
    fun `the SDK's own defaults collect everything both instruments need`() {
        val configuration = PayInFormConfiguration()

        PayInMethodType.entries.forEach { method ->
            assertEquals(
                "the default $method sections omit a field the instrument needs",
                emptySet<PayInField>(),
                PayInFieldRules.instrumentFields(method) - configuration.inputFieldsFor(method).toSet(),
            )
        }
    }

    @Test
    fun `every field demanded of the form is one the client refuses when it is blank`() =
        runTest {
            // The other half of the same requirement. Demanding a field the client accepts blank makes the
            // configuration stricter than the API for no reason; the reverse is the defect above.
            PayInMethodType.entries.forEach { method ->
                PayInFieldRules.instrumentFields(method).forEach { field ->
                    assertTrue(
                        "$method submits with a blank $field, so the form need not collect it",
                        refusalWithout(field, method) is PayInException.InvalidInput,
                    )
                }
            }
        }

    /** What the client says about an otherwise complete instrument with one field left out. */
    private suspend fun refusalWithout(
        field: PayInField,
        method: PayInMethodType,
    ): Throwable? =
        runCatching {
            val values = PayInFormValues(method, completeValues(method) - field)
            PayInFormInstrument.usePaymentMethod(values) {
                PayInValidation.paymentMethod(it, PayInValidationOptions())
            }
        }.exceptionOrNull()

    private fun refusedConfiguration(
        method: PayInMethodType,
        fields: List<PayInField>,
    ): Throwable? =
        runCatching {
            val sections = listOf(PayInFormSection(fields = fields))
            if (method == PayInMethodType.Card) {
                PayInFormConfiguration(allowedMethods = listOf(method), cardSections = sections)
            } else {
                PayInFormConfiguration(allowedMethods = listOf(method), bankSections = sections)
            }
        }.exceptionOrNull()

    private fun completeValues(method: PayInMethodType): Map<PayInField, String> =
        when (method) {
            PayInMethodType.Card ->
                mapOf(
                    PayInField.CardNumber to TEST_PAN,
                    PayInField.CardExpiration to TEST_EXPIRY,
                    PayInField.CardSecurityCode to TEST_SECURITY_CODE,
                    PayInField.CardholderName to "Ada Lovelace",
                    PayInField.CardPostalCode to "22039",
                )

            PayInMethodType.BankAccount ->
                mapOf(
                    PayInField.AccountNumber to TEST_ACCOUNT,
                    PayInField.RoutingNumber to TEST_ROUTING,
                    PayInField.AccountHolder to "Ada Lovelace",
                )
        }
}
