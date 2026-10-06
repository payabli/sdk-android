package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Total label is carried like every other label, and blank means the resource. */
class PayInFormLabelsTest {
    @Test
    fun `a caller's total label is read back`() {
        assertEquals("Due today", PayInFormLabels(total = "Due today").totalOrNull())
    }

    @Test
    fun `a null or blank total label means the resource`() {
        assertNull(PayInFormLabels().totalOrNull())
        assertNull(PayInFormLabels(total = "").totalOrNull())
        assertNull(PayInFormLabels(total = "   ").totalOrNull())
    }

    @Test
    fun `copy keeps the total label, and can replace it`() {
        val labels = PayInFormLabels(title = "Pay", total = "Due today")

        assertEquals("Due today", labels.copy(title = "Checkout").total)
        assertEquals("Owed", labels.copy(total = "Owed").total)
    }

    @Test
    fun `two labels differing only in total are not equal`() {
        val plain = PayInFormLabels(title = "Pay")
        val relabelled = PayInFormLabels(title = "Pay", total = "Due today")

        assertNotEquals(plain, relabelled)
        assertNotEquals(plain.hashCode(), relabelled.hashCode())
        assertEquals(relabelled, PayInFormLabels(title = "Pay", total = "Due today"))
        assertEquals(relabelled.hashCode(), PayInFormLabels(title = "Pay", total = "Due today").hashCode())
    }

    @Test
    fun `toString names the total label`() {
        assertTrue(PayInFormLabels(total = "Due today").toString().contains("total=Due today"))
    }
}
