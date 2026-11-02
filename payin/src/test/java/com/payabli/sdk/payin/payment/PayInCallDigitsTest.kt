package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.client.testAccount
import com.payabli.sdk.payin.model.PayInBankAccountData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInStoreRequest
import com.payabli.sdk.payin.model.SensitiveDigits
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each call works on a copy of the digits, overwritten however the call ends, and leaves the host's value as it was. */
class PayInCallDigitsTest {
    private class Refused : Exception()

    /** How a call can end: the three exits the copy has to be overwritten on. */
    private enum class Exit { SUCCEEDED, REFUSED, THREW }

    private suspend fun ending(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Refused) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun Exit.end() {
        when (this) {
            Exit.SUCCEEDED -> Unit
            Exit.REFUSED -> throw Refused()
            Exit.THREW -> throw IllegalStateException("the transport failed")
        }
    }

    @Test
    fun `a card's copy is overwritten on every exit and the host's card is not`() =
        runTest {
            Exit.entries.forEach { exit ->
                val card = testCardData()
                var sent: PayInCardData? = null

                ending {
                    PayInCallDigits.useCopy(PayInRequest(PayInPaymentMethod.Card(card), testOptions())) { copy ->
                        sent = (copy.paymentMethod as PayInPaymentMethod.Card).data
                        assertEquals(TEST_PAN, String(sent!!.cardNumberDigits.rawCopy()))
                        exit.end()
                    }
                }

                assertNotSame(card, sent)
                assertWiped("card number, $exit", sent!!.cardNumberDigits)
                assertWiped("security code, $exit", sent!!.securityCodeDigits)
                assertEquals(TEST_PAN, String(card.cardNumberDigits.rawCopy()))
                assertEquals(TEST_SECURITY_CODE, String(card.securityCodeDigits.rawCopy()))
            }
        }

    @Test
    fun `a bank account's copy is overwritten on every exit and the host's account is not`() =
        runTest {
            Exit.entries.forEach { exit ->
                val account = testAccount()
                var sent: PayInBankAccountData? = null

                ending {
                    PayInCallDigits.useCopy(PayInRequest(PayInPaymentMethod.BankAccount(account), testOptions())) {
                        sent = (it.paymentMethod as PayInPaymentMethod.BankAccount).data
                        exit.end()
                    }
                }

                assertWiped("account number, $exit", sent!!.accountNumberDigits)
                assertEquals(TEST_ACCOUNT, String(account.accountNumberDigits.rawCopy()))
            }
        }

    @Test
    fun `a stored method's copy is overwritten on every exit and the host's card is not`() =
        runTest {
            Exit.entries.forEach { exit ->
                val card = testCardData()
                var sent: PayInCardData? = null

                ending {
                    PayInCallDigits.useCopy(PayInStoreRequest(PayInInstrument.Card(card))) {
                        sent = (it.instrument as PayInInstrument.Card).data
                        exit.end()
                    }
                }

                assertWiped("card number, $exit", sent!!.cardNumberDigits)
                assertWiped("security code, $exit", sent!!.securityCodeDigits)
                assertEquals(TEST_PAN, String(card.cardNumberDigits.rawCopy()))
            }
        }

    private fun assertWiped(
        name: String,
        digits: SensitiveDigits,
    ) {
        assertTrue("the $name copy was not closed", digits.isWiped)
        assertTrue("the $name copy still holds characters", digits.rawCopy().all { it == SensitiveDigits.WIPED })
    }
}
