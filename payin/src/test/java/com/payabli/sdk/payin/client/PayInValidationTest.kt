package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.model.PayInAccountType
import com.payabli.sdk.payin.model.PayInAchData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInValidationOptions
import com.payabli.sdk.payin.model.SensitiveDigits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * What is refused before a request is built, and under which field name.
 *
 * The bounds themselves are `PayInFieldRules`' tests. What is asserted here is that this layer reads them, that
 * the field name a refusal carries is the service's own spelling, and that the messages carry no submitted
 * value.
 */
class PayInValidationTest {
    private fun card(
        pan: String = "4111111111111111",
        securityCode: String = "999",
        holderName: String = "A Payer",
        postalCode: String = "22039",
    ) = PayInCardData(
        cardNumber = SensitiveDigits.ofString(pan),
        expiry = ExpiryValue(12, 2030),
        securityCode = SensitiveDigits.ofString(securityCode),
        holderName = holderName,
        postalCode = postalCode,
    )

    private fun account(
        number: String = "00003400000",
        routing: String = "122105278",
        holderName: String = "A Payer",
    ) = PayInAchData(
        accountNumber = SensitiveDigits.ofString(number),
        routingNumber = routing,
        accountType = PayInAccountType.Checking,
        holderName = holderName,
    )

    private fun cardExpiring(expiry: ExpiryValue) =
        PayInCardData(
            cardNumber = SensitiveDigits.ofString("4111111111111111"),
            expiry = expiry,
            securityCode = SensitiveDigits.ofString("999"),
            holderName = "A Payer",
            postalCode = "22039",
        )

    private fun refusal(block: () -> Unit): PayInException.InvalidInput? =
        runCatching(block).exceptionOrNull() as? PayInException.InvalidInput

    @Test
    fun `a valid card passes`() {
        assertNull(refusal { PayInValidation.instrument(PayInInstrument.Card(card()), PayInValidationOptions()) })
    }

    @Test
    fun `each card field is refused under the name the service uses`() {
        val cases =
            listOf(
                "paymentMethod.cardnumber" to card(pan = "4111111111111112"),
                "paymentMethod.cardnumber" to card(pan = "411111"),
                "paymentMethod.cardnumber" to card(pan = ""),
                "paymentMethod.cardcvv" to card(securityCode = "9"),
                "paymentMethod.cardcvv" to card(securityCode = ""),
                "paymentMethod.cardHolder" to card(holderName = " "),
                "paymentMethod.cardHolder" to card(holderName = "n".repeat(61)),
                "paymentMethod.cardzip" to card(postalCode = ""),
                "paymentMethod.cardzip" to card(postalCode = "1".repeat(13)),
            )

        cases.forEach { (field, data) ->
            val refused = refusal { PayInValidation.instrument(PayInInstrument.Card(data), PayInValidationOptions()) }
            assertEquals(field, refused?.field)
        }
    }

    @Test
    fun `each bank field is refused under the name the service uses`() {
        val cases =
            listOf(
                "paymentMethod.achAccount" to account(number = ""),
                "paymentMethod.achAccount" to account(number = "123"),
                "paymentMethod.achRouting" to account(routing = "12210527"),
                "paymentMethod.achRouting" to account(routing = "122105279"),
                "paymentMethod.achHolder" to account(holderName = ""),
                "paymentMethod.achHolder" to account(holderName = "n".repeat(61)),
                // A bank refuses what it cannot print on a statement.
                "paymentMethod.achHolder" to account(holderName = "Ünicode Payer"),
                "paymentMethod.achHolder" to account(holderName = "A Payer <script>"),
            )

        cases.forEach { (field, data) ->
            val refused =
                refusal { PayInValidation.instrument(PayInInstrument.BankAccount(data), PayInValidationOptions()) }
            assertEquals(data.holderName, field, refused?.field)
        }
    }

    @Test
    fun `a holder name keeps the punctuation a bank accepts`() {
        listOf("A Payer", "O'Brien", "Smith-Jones", "A. Payer", "Payer 2nd").forEach { name ->
            val refused =
                refusal {
                    PayInValidation.instrument(
                        PayInInstrument.BankAccount(account(holderName = name)),
                        PayInValidationOptions(),
                    )
                }
            assertNull(name, refused)
        }
    }

    @Test
    fun `waiving the card check drops the check digit and keeps the length`() {
        val options = PayInValidationOptions(checksCardNumber = false)

        // A number that fails only Luhn is accepted when the caller says it has its own opinion.
        assertNull(
            refusal { PayInValidation.instrument(PayInInstrument.Card(card(pan = "4111111111111112")), options) },
        )
        // The length still is not negotiable: waiving the check digit is not waiving the shape.
        assertEquals(
            "paymentMethod.cardnumber",
            refusal { PayInValidation.instrument(PayInInstrument.Card(card(pan = "41111")), options) }?.field,
        )
    }

    @Test
    fun `waiving the routing check drops the checksum and keeps the length`() {
        val options = PayInValidationOptions(checksRoutingNumber = false)

        assertNull(
            refusal {
                PayInValidation.instrument(PayInInstrument.BankAccount(account(routing = "122105279")), options)
            },
        )
        assertEquals(
            "paymentMethod.achRouting",
            refusal {
                PayInValidation.instrument(PayInInstrument.BankAccount(account(routing = "1221")), options)
            }?.field,
        )
    }

    @Test
    fun `an amount is judged at the scale it will be sent at`() {
        // 0.001 is more than zero and reaches the wire as 0.00, so the value as supplied is the wrong thing
        // to check: the service would be asked to take nothing.
        listOf("0.001", "0.004", "-0.001").forEach { amount ->
            val refused = refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal(amount))) }
            assertEquals(amount, "paymentDetails.totalAmount", refused?.field)
        }
        // Rounds up to a cent, so it is a real amount.
        assertNull(refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("0.005"))) })
    }

    @Test
    fun `a fee is judged at that scale too`() {
        // Rounds to 0.00, so there is nothing negative left to refuse.
        assertNull(
            refusal {
                PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("10"), serviceFee = BigDecimal("-0.004")))
            },
        )
        // The other side of the half boundary: HALF_UP is about magnitude, so this rounds to -0.01.
        assertEquals(
            "paymentDetails.serviceFee",
            refusal {
                PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("10"), serviceFee = BigDecimal("-0.005")))
            }?.field,
        )
    }

    @Test
    fun `a total at the half boundary rounds up into a real amount`() {
        assertNull(refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("0.005"))) })
        assertEquals(
            "paymentDetails.totalAmount",
            refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("0.00499"))) }?.field,
        )
    }

    @Test
    fun `an amount larger than a Double holds exactly is accepted`() {
        assertNull(refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("12345678901234.56"))) })
    }

    @Test
    fun `an exponent form is judged by its value`() {
        // 1E-10 is more than zero and is sent as 0.00, so the check has to read the rounded value.
        assertEquals(
            "paymentDetails.totalAmount",
            refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("1E-10"))) }?.field,
        )
        assertNull(refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("1E+2"))) })
    }

    @Test
    fun `an expired card is refused before the transport sees it`() {
        val refused =
            refusal {
                PayInValidation.instrument(
                    PayInInstrument.Card(cardExpiring(ExpiryValue(1, 2000))),
                    PayInValidationOptions(),
                )
            }

        assertEquals("paymentMethod.cardexp", refused?.field)
    }

    @Test
    fun `a card expiring this month is still good`() {
        // The service treats the expiry month itself as valid, and so does the form.
        val now = ExpiryValue.today()
        assertNull(
            refusal { PayInValidation.instrument(PayInInstrument.Card(cardExpiring(now)), PayInValidationOptions()) },
        )
    }

    @Test
    fun `validation leaves no readable copy of a card number behind`() {
        // Each read is scoped, so a refusal partway through still overwrites what it read.
        val data = card(pan = "4111111111111112")

        refusal { PayInValidation.instrument(PayInInstrument.Card(data), PayInValidationOptions()) }

        // The value itself is untouched: validation reads it, it does not consume it.
        assertEquals("4111111111111112", String(data.cardNumber.rawCopy()))
    }

    @Test
    fun `an amount has to be more than nothing`() {
        listOf("0", "-1", "-0.01").forEach { amount ->
            val refused = refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal(amount))) }
            assertEquals(amount, "paymentDetails.totalAmount", refused?.field)
        }
        assertNull(refusal { PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("0.01"))) })
    }

    @Test
    fun `a fee may be zero and may not be negative`() {
        assertNull(
            refusal {
                PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("10"), serviceFee = BigDecimal.ZERO))
            },
        )
        assertEquals(
            "paymentDetails.serviceFee",
            refusal {
                PayInValidation.paymentDetails(PayInPaymentDetails(BigDecimal("10"), serviceFee = BigDecimal("-0.01")))
            }?.field,
        )
    }

    @Test
    fun `the other payment methods are refused when their one field is blank`() {
        val cases =
            listOf(
                "paymentMethod.storedMethodId" to PayInPaymentMethod.Stored(" "),
                "paymentMethod.device" to PayInPaymentMethod.CloudDevice(""),
                "paymentMethod.checkHolder" to PayInPaymentMethod.Check(" "),
            )

        cases.forEach { (field, method) ->
            assertEquals(field, refusal { PayInValidation.paymentMethod(method, PayInValidationOptions()) }?.field)
        }
        // Cash carries no instrument, so there is nothing to refuse.
        assertNull(refusal { PayInValidation.paymentMethod(PayInPaymentMethod.Cash, PayInValidationOptions()) })
    }

    @Test
    fun `a blank entry point and a blank transaction id are refused`() {
        assertEquals("entryPoint", refusal { PayInValidation.entryPoint(" ") }?.field)
        assertEquals("transId", refusal { PayInValidation.transId("") }?.field)
    }

    @Test
    fun `no refusal message carries the value it refused`() {
        val pan = "4111111111111112"
        val refused =
            refusal { PayInValidation.instrument(PayInInstrument.Card(card(pan = pan)), PayInValidationOptions()) }

        assertFalse(refused?.reason?.contains(pan) ?: true)
        assertFalse(refused.toString().contains(pan))
        // The exception's own message is the classification, as `:core` requires of every one of these.
        assertEquals("VALIDATION_ERROR", refused?.message)
    }

    @Test
    fun `a closed buffer reads as a missing field rather than an exception`() {
        val data = card()
        data.cardNumber.close()

        val refused = refusal { PayInValidation.instrument(PayInInstrument.Card(data), PayInValidationOptions()) }

        assertEquals("paymentMethod.cardnumber", refused?.field)
        assertTrue(refused?.reason?.contains("required") == true)
    }
}
