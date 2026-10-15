package com.payabli.sdk.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** A bridge forwards the declaration by name, so renaming or reordering a case breaks every binding. */
class PayabliAttemptTest {
    @Test
    fun `the declarations keep their names and order`() {
        assertEquals(listOf("NewPayment", "Retry"), PayabliAttempt.entries.map { it.name })
        assertEquals(PayabliAttempt.Retry, PayabliAttempt.valueOf("Retry"))
    }
}
