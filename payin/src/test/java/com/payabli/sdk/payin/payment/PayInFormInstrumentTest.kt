package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.model.PayInAchData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.SensitiveDigits
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What the form's values become, and what is left behind afterwards.
 *
 * The buffer guarantee is tested here rather than through the holder, because this is the unit that creates
 * the buffers: the instrument never escapes these two functions in production, so a test that reaches it
 * through a submission would be reaching through two layers to assert about one.
 */
class PayInFormInstrumentTest {
    private val timeout = 5.seconds

    @Test
    fun `a card's buffers are overwritten once the block has returned`() =
        runTest(timeout = timeout) {
            var retained: PayInCardData? = null

            PayInFormInstrument.usePaymentMethod(cardForm()) { method ->
                retained = (method as PayInPaymentMethod.Card).data
                // Readable inside the block, which is the whole window they exist for.
                assertEquals(TEST_PAN.length, retained?.cardNumber?.length)
            }

            assertWiped("card number", retained?.cardNumber)
            assertWiped("security code", retained?.securityCode)
        }

    @Test
    fun `a card's buffers are overwritten when the block throws`() =
        runTest(timeout = timeout) {
            var retained: PayInCardData? = null

            val raised =
                runCatching {
                    PayInFormInstrument.usePaymentMethod(cardForm()) { method ->
                        retained = (method as PayInPaymentMethod.Card).data
                        throw IllegalStateException("the socket went away")
                    }
                }.exceptionOrNull()

            assertTrue("the block's failure was swallowed", raised is IllegalStateException)
            assertWiped("card number", retained?.cardNumber)
            assertWiped("security code", retained?.securityCode)
        }

    @Test
    fun `a bank account's buffer is overwritten too`() =
        runTest(timeout = timeout) {
            var retained: PayInAchData? = null

            PayInFormInstrument.useInstrument(bankForm()) { instrument ->
                retained = (instrument as PayInInstrument.BankAccount).data
                assertEquals(TEST_ACCOUNT.length, retained?.accountNumber?.length)
            }

            assertWiped("account number", retained?.accountNumber)
        }

    // --- what the values map to ---

    @Test
    fun `a card carries what the payer typed, and the expiry as a value rather than text`() =
        runTest(timeout = timeout) {
            PayInFormInstrument.usePaymentMethod(cardForm()) { method ->
                val card = (method as PayInPaymentMethod.Card).data
                assertEquals("Integration Test", card.holderName)
                assertEquals("22039", card.postalCode)
                assertEquals(12, card.expiry.month)
                assertEquals(TEST_SECURITY_CODE.length, card.securityCode.length)
            }
        }

    @Test
    fun `the choices the form leaves empty take the service's own defaults`() =
        runTest(timeout = timeout) {
            PayInFormInstrument.useInstrument(bankForm()) { instrument ->
                val account = (instrument as PayInInstrument.BankAccount).data
                assertEquals("Checking", account.accountType.wireName)
                assertNull("a holder type was invented", account.holderType)
                assertNull("an authorization code was invented", account.secCode)
                assertNull("a device was invented", account.deviceId)
            }
        }

    @Test
    fun `a choice this SDK does not offer is refused before a buffer exists`() =
        runTest(timeout = timeout) {
            val raised =
                runCatching {
                    PayInFormInstrument.useInstrument(bankForm(secCode = "iat")) { }
                }.exceptionOrNull()

            val refusal = raised as? PayInException.InvalidInput
            assertTrue("$raised", refusal != null)
            assertEquals("paymentMethod.achCode", refusal?.field)
        }

    @Test
    fun `an expiry the picker could not have produced is refused, naming the expiry`() =
        runTest(timeout = timeout) {
            val raised =
                runCatching {
                    PayInFormInstrument.usePaymentMethod(cardForm(expiry = "not a month")) { }
                }.exceptionOrNull()

            assertEquals("paymentMethod.cardexp", (raised as? PayInException.InvalidInput)?.field)
        }

    @Test
    fun `an unknown field is empty rather than absent`() =
        runTest(timeout = timeout) {
            // A configuration whose card section omits the postal code reports no value for it, and the
            // instrument has to be buildable from what is there.
            val values = cardForm().let { it.values.filterKeys { key -> key != PayInField.CardPostalCode } }
            PayInFormInstrument.usePaymentMethod(
                com.payabli.sdk.payin.form
                    .PayInFormValues(com.payabli.sdk.payin.form.PayInMethodType.Card, values),
            ) { method ->
                assertEquals("", (method as PayInPaymentMethod.Card).data.postalCode)
            }
        }

    /**
     * The buffer as it stands, read through the array behind it.
     *
     * `SensitiveDigits.read` answers empty once wiped, which is right for a reader and proves nothing here.
     */
    private fun assertWiped(
        name: String,
        digits: SensitiveDigits?,
    ) {
        val value = requireNotNull(digits) { "the block never received an instrument" }
        assertTrue("the $name was not overwritten", value.isWiped)
        assertTrue("the $name buffer still holds characters", value.rawCopy().all { it == SensitiveDigits.WIPED })
    }
}
