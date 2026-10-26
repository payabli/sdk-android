package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Total label and the failure messages are carried like every other label, and blank means the resource. */
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

    @Test
    fun `a caller's failure messages are read back`() {
        assertEquals("No", PayInFormLabels(declinedMessage = "No").declinedMessageOrNull())
        assertEquals("Again", PayInFormLabels(retryLaterMessage = "Again").retryLaterMessageOrNull())
        assertEquals("Check", PayInFormLabels(outcomeUnknownMessage = "Check").outcomeUnknownMessageOrNull())
        assertEquals("Other", PayInFormLabels(otherMessage = "Other").otherMessageOrNull())
    }

    @Test
    fun `a null or blank failure message means the resource`() {
        assertNull(PayInFormLabels().declinedMessageOrNull())
        assertNull(PayInFormLabels(declinedMessage = "").declinedMessageOrNull())
        assertNull(PayInFormLabels(retryLaterMessage = "   ").retryLaterMessageOrNull())
        assertNull(PayInFormLabels(outcomeUnknownMessage = " ").outcomeUnknownMessageOrNull())
        assertNull(PayInFormLabels(otherMessage = "").otherMessageOrNull())
    }

    @Test
    fun `copy keeps the failure messages, and can replace them`() {
        val labels = PayInFormLabels(title = "Pay", declinedMessage = "No")

        assertEquals("No", labels.copy(title = "Checkout").declinedMessage)
        assertEquals("Not taken", labels.copy(declinedMessage = "Not taken").declinedMessage)
    }

    @Test
    fun `two labels differing only in a failure message are not equal`() {
        val plain = PayInFormLabels(title = "Pay")

        listOf(
            PayInFormLabels(title = "Pay", declinedMessage = "No"),
            PayInFormLabels(title = "Pay", retryLaterMessage = "No"),
            PayInFormLabels(title = "Pay", outcomeUnknownMessage = "No"),
            PayInFormLabels(title = "Pay", otherMessage = "No"),
        ).forEach { relabelled ->
            assertNotEquals(plain, relabelled)
            assertNotEquals(plain.hashCode(), relabelled.hashCode())
        }
    }

    @Test
    fun `toString names the failure messages`() {
        val text =
            PayInFormLabels(
                declinedMessage = "No",
                retryLaterMessage = "Again",
                outcomeUnknownMessage = "Check",
                otherMessage = "Other",
            ).toString()

        assertTrue(text.contains("declinedMessage=No"))
        assertTrue(text.contains("retryLaterMessage=Again"))
        assertTrue(text.contains("outcomeUnknownMessage=Check"))
        assertTrue(text.contains("otherMessage=Other"))
    }
}
