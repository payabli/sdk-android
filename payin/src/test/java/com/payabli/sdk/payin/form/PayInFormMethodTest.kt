package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The method on screen is the form's, so a configuration from another flow keeps it wherever it offers it. */
class PayInFormMethodTest {
    private val both =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
            defaultMethod = PayInMethodType.Card,
        )

    private val method = PayInFormMethod()

    @Test
    fun aFormOpensOnTheStartingMethodAndHasToldNothing() {
        method.open(both.copy(defaultMethod = PayInMethodType.BankAccount))

        assertEquals(PayInMethodType.BankAccount, method.current)
        assertNull(method.told)
    }

    @Test
    fun aConfigurationThatOffersTheMethodOnScreenKeepsIt() {
        method.open(both)
        method.switchTo(PayInMethodType.BankAccount)

        method.open(both.copy(defaultMethod = PayInMethodType.Card))

        assertEquals(PayInMethodType.BankAccount, method.current)
    }

    @Test
    fun aConfigurationThatDoesNotOfferItOpensOnItsStartingMethod() {
        method.open(both)
        method.switchTo(PayInMethodType.BankAccount)

        method.open(both.copy(allowedMethods = listOf(PayInMethodType.Card)))

        assertEquals(PayInMethodType.Card, method.current)
    }

    @Test
    fun readingTheMethodBeforeTheFormOpensFails() {
        val thrown = runCatching { PayInFormMethod().current }.exceptionOrNull()

        assertTrue("an unopened form answered $thrown", thrown is IllegalStateException)
    }
}
