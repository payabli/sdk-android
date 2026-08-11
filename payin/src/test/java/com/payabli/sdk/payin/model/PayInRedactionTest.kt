package com.payabli.sdk.payin.model

import com.payabli.sdk.payin.form.ExpiryValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Every public type's `toString`, against the values it is holding.
 *
 * `toString` reaches a crash reporter, a debugger and any log line that interpolates an object, and none of
 * those redact. Each type here carries something that must not travel: a card number, an amount, a payer's
 * name, or server text that echoes what was submitted.
 */
class PayInRedactionTest {
    private val pan = "4111111111111111"
    private val securityCode = "999"
    private val account = "00003400000"

    private fun card() =
        PayInCardData(
            cardNumber = SensitiveDigits.ofString(pan),
            expiry = ExpiryValue(12, 2030),
            securityCode = SensitiveDigits.ofString(securityCode),
            holderName = "A Payer",
            postalCode = "22039",
        )

    @Test
    fun `card data carries no number, code, or holder`() {
        val rendered = card().toString()

        listOf(pan, securityCode, "A Payer", "22039").forEach { assertFalse(it, rendered.contains(it)) }
        // The expiry year is not a secret and is what makes one card distinguishable from another in a report.
        assertTrue(rendered.contains("2030"))
    }

    @Test
    fun `bank data carries no account number`() {
        val data =
            PayInAchData(
                accountNumber = SensitiveDigits.ofString(account),
                routingNumber = "122105278",
                accountType = PayInAccountType.Checking,
                holderName = "A Payer",
            )

        val rendered = data.toString()

        listOf(account, "A Payer").forEach { assertFalse(it, rendered.contains(it)) }
        assertTrue(rendered.contains("Checking"))
    }

    @Test
    fun `payment details carry no amount`() {
        val rendered = PayInPaymentDetails(BigDecimal("1234.56"), serviceFee = BigDecimal("7.89")).toString()

        assertFalse(rendered.contains("1234.56"))
        assertFalse(rendered.contains("7.89"))
        assertTrue(rendered.contains("hasServiceFee=true"))
    }

    @Test
    fun `customer data carries no personal detail`() {
        val rendered =
            PayInCustomerData(
                firstName = "Ada",
                lastName = "Lovelace",
                billingEmail = "ada@example.com",
                billingZip = "90001",
            ).toString()

        listOf("Ada", "Lovelace", "ada@example.com", "90001").forEach { assertFalse(it, rendered.contains(it)) }
    }

    @Test
    fun `a stored method carries its presence and not its text`() {
        val rendered = PayInStoredMethod("tok-77", 501L, 88L, 1, "Approved on file").toString()

        assertFalse(rendered.contains("Approved on file"))
        assertTrue(rendered.contains("hasId=true"))
    }

    @Test
    fun `a transaction carries no amount or customer`() {
        val rendered =
            PayInTransaction(
                paymentTransId = "101-abc",
                gatewayTransId = "gtw-9",
                orderId = "order-1",
                method = "card",
                transStatus = 1,
                paypointId = 42L,
                totalAmount = BigDecimal("1234.56"),
                netAmount = BigDecimal("1200.00"),
                connectorName = "fiserv",
                customerId = 88L,
            ).toString()

        listOf("1234.56", "1200.00", "88").forEach { assertFalse(it, rendered.contains(it)) }
        assertTrue(rendered.contains("hasTransId=true"))
    }

    @Test
    fun `a result and a failure carry the code and not the prose`() {
        assertEquals("PayInResult(code=A0000)", PayInResult("A0000", null).toString())

        val echoing = "Card $pan was refused"
        val failure = PayInFailure("D0001", echoing, "Try another card", "r", 200)

        assertFalse(failure.toString().contains(pan))
        assertTrue(failure.toString().contains("D0001"))
    }

    @Test
    fun `every failure's message is the classification, never the prose`() {
        val echoing = "Card $pan was refused"
        val refused = PayInException.Refused(PayInFailure("D0001", echoing, null, null, 200))
        val invalid = PayInException.InvalidInput("paymentMethod.cardnumber", "The card number is not valid")
        val undecodable = PayInException.Undecodable(IllegalStateException(echoing))

        // `:core` requires the message to be the code, so a stack trace carries the classification rather than
        // text that may echo what was submitted.
        assertEquals("PAYMENT_DECLINED", refused.message)
        assertEquals("VALIDATION_ERROR", invalid.message)
        assertEquals("DECODING_ERROR", undecodable.message)
        listOf(refused, invalid, undecodable).forEach { assertFalse(it.toString().contains(pan)) }
    }

    @Test
    fun `an undecodable failure keeps the cause's type and drops its message`() {
        val cause = IllegalStateException("Unexpected JSON token at offset 42: $pan")

        val rendered =
            PayInException
                .Undecodable(cause)
                .cause
                ?.message
                .orEmpty()

        assertTrue(rendered.contains("IllegalStateException"))
        assertFalse(rendered.contains(pan))
    }

    @Test
    fun `the reason a caller displays is still the server's`() {
        // Redaction is about what is written down, not about what a payer is shown: the reason stays readable
        // on the exception a caller catches.
        val refused = PayInException.Refused(PayInFailure("D0329", "Insufficient funds", "No funds", "r", 200))

        assertEquals("Insufficient funds", refused.reason)
        assertEquals("No funds", refused.detail)
    }
}
