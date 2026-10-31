package com.payabli.sdk.taptopay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fields a customer-data refusal carries refuse to be changed: not by a later edit to the list it
 * was built from, and not by whoever receives them. Fixtures hold two fields, because `toList` is already
 * immutable below that and a smaller fixture would pass without the copy.
 */
class TapToPayChargeRefusalTest {
    private fun refusal() =
        TapToPayChargeRefusal.CustomerDataRefused(
            mutableListOf("customernumber", "customerid"),
        )

    @Test
    fun `the fields cannot be cleared by whoever receives them`() {
        val requiredFields = refusal().requiredFields

        val refused = runCatching { (requiredFields as java.util.Collection<*>).clear() }.exceptionOrNull()

        assertTrue("a caller cleared the fields this refusal handed out", refused is UnsupportedOperationException)
        assertEquals(2, requiredFields.size)
    }

    @Test
    fun `changing the list that was passed in does not change what is carried`() {
        val supplied = mutableListOf("customernumber", "customerid")
        val refusal = TapToPayChargeRefusal.CustomerDataRefused(supplied)

        supplied.clear()

        assertEquals(listOf("customernumber", "customerid"), refusal.requiredFields)
    }
}
