package com.payabli.sdk.core.network.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private const val VERSION_7 = 7
private const val VARIANT_RFC4122 = 2
private const val SAMPLE_COUNT = 500

/**
 * The fields, not only that two values differ. A wrong bit position still produces something that parses
 * and prints like a UUID, so distinctness alone would pass on a malformed value.
 */
class UuidV7Test {
    @Test
    fun `the version field is 7`() {
        assertEquals(VERSION_7, UuidV7.next().version())
    }

    @Test
    fun `the variant field is the one RFC 9562 specifies`() {
        assertEquals(VARIANT_RFC4122, UuidV7.next().variant())
    }

    /**
     * Pins the timestamp field to the wall clock, and that is also what makes a separate time-ordering
     * test unnecessary here: a frozen or constant timestamp fails this, and ordering beyond that is the
     * clock's property rather than this generator's.
     */
    @Test
    fun `the timestamp field reads back as the millisecond it was minted in`() {
        val before = System.currentTimeMillis()
        val value = UuidV7.next()
        val after = System.currentTimeMillis()

        val stamped = UuidV7.timestampMillisOf(value)
        assertTrue("$stamped is outside $before..$after", stamped in before..after)
    }

    /**
     * What separates two values minted in one millisecond is the random fields, so they are compared
     * directly. Grouping a sample by timestamp and requiring a shared one instead reads as the same
     * assertion and is not: whether any two of a sample land in one millisecond is the scheduler's to
     * decide, so that shape fails on correct code on a loaded machine.
     */
    @Test
    fun `the random fields differ between values, whatever their timestamps`() {
        val fields = List(SAMPLE_COUNT) { UuidV7.randomFieldsOf(UuidV7.next()) }
        assertEquals(SAMPLE_COUNT, fields.toSet().size)
    }

    @Test
    fun `a value round-trips through its text form`() {
        val value = UuidV7.next()
        assertEquals(value, UUID.fromString(value.toString()))
    }
}
