package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliFieldError
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which box a refusal marks.
 *
 * One case per field a refusal can name. A missing case is a payer reading that their card number is wrong
 * beside four unmarked boxes.
 */
class PayInRefusedFieldsTest {
    @Test
    fun `every name this module refuses under is a field the form draws`() {
        val expected =
            mapOf(
                "paymentMethod.cardnumber" to PayInField.CardNumber,
                "paymentMethod.cardexp" to PayInField.CardExpiration,
                "paymentMethod.cardcvv" to PayInField.CardSecurityCode,
                "paymentMethod.cardHolder" to PayInField.CardholderName,
                "paymentMethod.cardzip" to PayInField.CardPostalCode,
                "paymentMethod.achAccount" to PayInField.AccountNumber,
                "paymentMethod.achAccountType" to PayInField.AccountType,
                "paymentMethod.achRouting" to PayInField.RoutingNumber,
                "paymentMethod.achHolder" to PayInField.AccountHolder,
                "paymentMethod.achHolderType" to PayInField.AccountHolderType,
                "paymentMethod.achCode" to PayInField.SecCode,
                "paymentMethod.device" to PayInField.DeviceId,
                "paymentDetails.totalAmount" to PayInField.Amount,
                "paymentDetails.serviceFee" to PayInField.ServiceFee,
                // The customer half, which the request started carrying with `PayInEnteredDetails`.
                "customerData.firstName" to PayInField.FirstName,
                "customerData.lastName" to PayInField.LastName,
                "customerData.customerNumber" to PayInField.CustomerNumber,
                "customerData.billingEmail" to PayInField.BillingEmail,
                "customerData.billingZip" to PayInField.BillingPostalCode,
                "methodDescription" to PayInField.MethodDescription,
            )

        expected.forEach { (name, field) ->
            assertEquals(
                name,
                mapOf(field to PayInFieldError.NotAccepted),
                PayInRefusedFields.of(PayInException.InvalidInput(name, "not valid")),
            )
        }
    }

    @Test
    fun `the same field is found whether the service sends it bare or under its parent`() {
        // ASP.NET model validation reports the property on its own, and this module reports it under the object
        // that carries it.
        listOf("cardnumber", "paymentMethod.cardnumber", "CARDNUMBER", "PaymentMethod.CardNumber").forEach { name ->
            assertEquals(
                name,
                mapOf(PayInField.CardNumber to PayInFieldError.NotAccepted),
                PayInRefusedFields.of(validation(name)),
            )
        }
    }

    @Test
    fun `a refusal naming something the payer never typed marks nothing`() {
        // The entry point is configuration, a stored method identifier is a token, and `$` is the whole body.
        listOf("entryPoint", "paymentMethod.storedMethodId", "$", "somethingElse").forEach { name ->
            assertTrue(name, PayInRefusedFields.of(validation(name)).isEmpty())
            assertTrue(name, PayInRefusedFields.of(PayInException.InvalidInput(name, "not valid")).isEmpty())
        }
    }

    @Test
    fun `a validation refusal naming several fields marks all of them`() {
        val failure =
            PayabliValidationException(
                httpStatus = 400,
                fieldErrors =
                    mapOf(
                        "paymentMethod.cardnumber" to listOf(PayabliFieldError("The card number is not valid.")),
                        "paymentMethod.cardzip" to listOf(PayabliFieldError("The postal code is required.")),
                        "entryPoint" to listOf(PayabliFieldError("The Entry field is required.")),
                    ),
            )

        assertEquals(
            mapOf(
                PayInField.CardNumber to PayInFieldError.NotAccepted,
                PayInField.CardPostalCode to PayInFieldError.NotAccepted,
            ),
            PayInRefusedFields.of(failure),
        )
    }

    @Test
    fun `a refusal that names no field at all marks nothing`() {
        // A decline is about the account rather than about a value on the form, and neither is a network failure.
        val decline = PayInException.Refused(PayInFailure("D1001", "Insufficient funds", null, "retry", 200))
        assertTrue(PayInRefusedFields.of(decline).isEmpty())
        assertTrue(PayInRefusedFields.of(PayInException.InvalidInput(null, "not valid")).isEmpty())
        assertTrue(PayInRefusedFields.of(PayabliValidationException(httpStatus = 400)).isEmpty())
        assertTrue(PayInRefusedFields.of(IllegalStateException("something else")).isEmpty())
    }

    private fun validation(field: String): PayabliValidationException =
        PayabliValidationException(
            httpStatus = 400,
            fieldErrors = mapOf(field to listOf(PayabliFieldError("not accepted"))),
        )
}
