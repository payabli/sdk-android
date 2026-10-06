package com.payabli.sdk.core.device

import org.junit.Assert.assertEquals
import org.junit.Test

class UsableValueCacheTest {
    @Test
    fun `an unusable read is not held, so the next call reads again`() {
        val answers = ArrayDeque(listOf("", "identifier"))
        var reads = 0
        val cache = UsableValueCache<String> { it.isNotBlank() }

        val first =
            cache.get {
                reads++
                answers.removeFirst()
            }
        val second =
            cache.get {
                reads++
                answers.removeFirst()
            }

        assertEquals("", first)
        assertEquals("identifier", second)
        assertEquals(2, reads)
    }

    @Test
    fun `a usable read is held, so nothing is read again`() {
        var reads = 0
        val cache = UsableValueCache<String> { it.isNotBlank() }

        repeat(3) {
            assertEquals(
                "identifier",
                cache.get {
                    reads++
                    "identifier"
                },
            )
        }

        assertEquals(1, reads)
    }
}
