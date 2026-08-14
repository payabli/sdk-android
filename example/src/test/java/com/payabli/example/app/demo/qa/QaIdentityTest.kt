package com.payabli.example.app.demo.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * The one property the whole exercise rests on: two devices in a run never produce the same values.
 *
 * The models here are the three phones and the simulator the run uses, spelled as each platform reports them.
 */
class QaIdentityTest {
    @Test
    fun `the devices in a run share no value`() {
        val identities = RUN_MODELS.map(QaIdentity::from)

        listOf<(QaIdentity) -> String>(
            { it.label },
            { it.slug },
            { it.holderName },
            { it.lastName },
            { it.customerNumber },
            { it.billingEmail },
            { it.note("capture") },
        ).forEach { read ->
            val values = identities.map(read)
            assertEquals("two devices answered the same: $values", values.size, values.distinct().size)
        }
    }

    @Test
    fun `an account holder name carries nothing the store route refuses`() {
        // Measured on qa: `QA Samsung SM-S908U1` comes back "Bad Request: Account holder name cannot contain
        // special characters", and the same name without the hyphen is stored. Every model code has punctuation
        // in it, so this is every device rather than an unlucky one.
        RUN_MODELS.map(QaIdentity::from).forEach { identity ->
            assertTrue(
                "${identity.holderName} carries something other than a letter, a digit or a space",
                identity.holderName.all { it.isLetterOrDigit() || it == ' ' },
            )
            assertTrue("${identity.holderName} has a run of spaces in it", !identity.holderName.contains("  "))
        }

        assertEquals("QA Samsung SM S908U1", QaIdentity.from("samsung SM-S908U1").holderName)
    }

    @Test
    fun `a manufacturer is capitalised and a model code is left alone`() {
        assertEquals("Samsung SM-S908U1", QaIdentity.from("samsung SM-S908U1").label)
        assertEquals("Google Pixel 7a", QaIdentity.from("Google Pixel 7a").label)
    }

    @Test
    fun `the slug is safe in a customer number and an address`() {
        RUN_MODELS.map(QaIdentity::from).forEach { identity ->
            assertTrue(
                "${identity.slug} carries something other than a letter, a digit or a dash",
                identity.slug.all { it.isLowerCase() && it.isLetterOrDigit() || it.isDigit() || it == '-' },
            )
            assertTrue("${identity.slug} starts or ends with a dash", !identity.slug.startsWith("-"))
            assertTrue("${identity.slug} starts or ends with a dash", !identity.slug.endsWith("-"))
        }
    }

    @Test
    fun `a model that says nothing still names something`() {
        // `Build.MODEL` is a device property, and a custom ROM can leave it empty. An empty customer number is
        // a `400` that names no field.
        val identity = QaIdentity.from("   ")

        assertTrue(identity.label.isNotBlank())
        assertTrue(identity.slug.isNotBlank())
        assertEquals("qa-android-unknown-device", identity.customerNumber)
    }

    @Test
    fun `an order identifier carries the device and the second`() {
        // To the second, because a walk submits several a minute apart.
        val identity = QaIdentity.from("Google Pixel 7a")
        val noon = stamp(hour = 12, minute = 0, second = 0)
        val secondLater = stamp(hour = 12, minute = 0, second = 1)

        assertEquals("google-pixel-7a-20260814-120000", identity.orderId(noon))
        assertNotEquals(identity.orderId(noon), identity.orderId(secondLater))
    }

    /** 2026-08-14 in the default zone, which is what [QaIdentity.orderId] formats in. */
    private fun stamp(
        hour: Int,
        minute: Int,
        second: Int,
    ): Long =
        GregorianCalendar(TimeZone.getDefault())
            .apply { set(2026, Calendar.AUGUST, 14, hour, minute, second) }
            .timeInMillis

    private companion object {
        val RUN_MODELS =
            listOf("Google Pixel 7a", "samsung SM-S908U1", "samsung SM-A136U1", "iPhone 17")
    }
}
