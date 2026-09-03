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

    @Test
    fun `the timestamp field reads back as the millisecond it was minted in`() {
        val before = System.currentTimeMillis()
        val value = UuidV7.next()
        val after = System.currentTimeMillis()

        val stamped = UuidV7.timestampMillisOf(value)
        assertTrue("$stamped is outside $before..$after", stamped in before..after)
    }

    /** Time-ordering is the property this format was chosen for, so it is asserted rather than assumed. */
    @Test
    fun `a later value does not carry an earlier timestamp`() {
        val first = UuidV7.timestampMillisOf(UuidV7.next())
        val second = UuidV7.timestampMillisOf(UuidV7.next())
        assertTrue("$second went backwards from $first", second >= first)
    }

    @Test
    fun `values minted inside one millisecond are still distinct`() {
        val values = List(SAMPLE_COUNT) { UuidV7.next() }
        assertEquals(SAMPLE_COUNT, values.toSet().size)
    }

    @Test
    fun `a value round-trips through its text form`() {
        val value = UuidV7.next()
        assertEquals(value, UUID.fromString(value.toString()))
    }
}
