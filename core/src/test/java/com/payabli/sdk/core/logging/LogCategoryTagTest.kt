package com.payabli.sdk.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that stops a future `LogCategory.INSTRUMENT_DATA_ENCRYPTION` from crashing on an API 23
 * device: `Log.isLoggable` documents `IllegalArgumentException` for a tag longer than 23 characters on
 * API 25 and below, and this module's floor is 23.
 */
class LogCategoryTagTest {
    @Test
    fun everyTagFitsTheIsLoggableLimit() {
        LogCategory.entries.forEach {
            assertTrue(
                "${it.tag} is ${it.tag.length} chars; Log.isLoggable throws above " +
                    "${LogCategory.MAX_TAG_LENGTH} on API 25 and below",
                it.tag.length <= LogCategory.MAX_TAG_LENGTH,
            )
        }
    }

    @Test
    fun everyTagIsDistinct() {
        val tags = LogCategory.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun everyTagIsPayabliPrefixed() {
        // Keeps `adb logcat Payabli*:D *:S` selecting the whole SDK, which is how the single Android

        LogCategory.entries.forEach { assertTrue(it.tag, it.tag.startsWith("Payabli")) }
    }
}
