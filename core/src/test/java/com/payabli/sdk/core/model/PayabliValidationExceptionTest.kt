package com.payabli.sdk.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field errors this exception hands out refuse to be changed, at both levels.
 *
 * Kotlin's read-only `Map` and `List` are `LinkedHashMap` and `ArrayList` at runtime, and the lists here come
 * from the decoder, so both are reachable from Java. An exception whose contents can be edited after it is
 * thrown reports something other than what the service said.
 *
 * **Every fixture holds at least two entries.** `toMap` and `toList` answer with an immutable singleton for one
 * and an immutable empty for none, so a smaller fixture passes whether or not anything wrapped it.
 */
class PayabliValidationExceptionTest {
    private fun exception() =
        PayabliValidationException(
            httpStatus = 400,
            fieldErrors =
                mapOf(
                    "cardNumber" to
                        mutableListOf(
                            PayabliFieldError("is required"),
                            PayabliFieldError("is not a card number"),
                        ),
                    "firstName" to
                        mutableListOf(
                            PayabliFieldError("is required"),
                            PayabliFieldError("is too long"),
                        ),
                ),
        )

    @Test
    fun `the map cannot be cleared by whoever receives it`() {
        val fieldErrors = exception().fieldErrors

        val refused = runCatching { (fieldErrors as java.util.Map<*, *>).clear() }.exceptionOrNull()

        assertTrue("a caller cleared the map this exception handed out", refused is UnsupportedOperationException)
        assertEquals(2, fieldErrors.size)
    }

    @Test
    fun `a field's failures cannot be cleared by whoever receives them`() {
        val failures = exception().fieldErrors.getValue("cardNumber")

        val refused = runCatching { (failures as java.util.Collection<*>).clear() }.exceptionOrNull()

        assertTrue("a caller cleared a list this exception handed out", refused is UnsupportedOperationException)
        assertEquals(2, failures.size)
    }

    @Test
    fun `changing the list that was passed in does not change what is reported`() {
        val supplied = mutableListOf(PayabliFieldError("is required"), PayabliFieldError("is not a card number"))
        val exception = PayabliValidationException(httpStatus = 400, fieldErrors = mapOf("cardNumber" to supplied))

        supplied.clear()

        assertEquals(2, exception.fieldErrors.getValue("cardNumber").size)
    }
}
