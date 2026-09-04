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

    @Test
    fun `values sharing a millisecond are still distinct`() {
        val values = List(SAMPLE_COUNT) { UuidV7.next() }
        val sharing = values.groupBy { UuidV7.timestampMillisOf(it) }.values.filter { it.size > 1 }

        // Asserted first, because without it the run proves nothing: if every mint landed in its own
        // millisecond, the timestamp alone would separate the values and a fixed random field would pass.
        assertTrue("no two of $SAMPLE_COUNT values shared a millisecond", sharing.isNotEmpty())
        sharing.forEach { group -> assertEquals(group.size, group.toSet().size) }
    }

    @Test
    fun `a value round-trips through its text form`() {
        val value = UuidV7.next()
        assertEquals(value, UUID.fromString(value.toString()))
    }
}
