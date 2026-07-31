package com.payabli.sdk.core.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The fixture has to reject what the shipping store rejects.
 *
 * A test double that accepts more than production lets a consumer's test pass here and fail on a device, which is
 * the one failure mode a fixture must not have. These assert the agreement, not the map.
 */
class InMemorySecureStorageTest {
    @Test
    fun `an unrepresentable key is rejected by get, set and remove`() =
        runTest(timeout = 5.seconds) {
            val subject = InMemorySecureStorage()
            // Encodes to the single byte 0x3f, the same as "\uD801" and a literal "?", so as an AAD it is not a
            // distinct name. FileSecureStorage rejects it; so must this.
            val malformed = "\uD800"

            val onGet = runCatching { subject.get(malformed) }.exceptionOrNull()
            val onSet = runCatching { subject.set(malformed, "x".toByteArray()) }.exceptionOrNull()
            val onRemove = runCatching { subject.remove(malformed) }.exceptionOrNull()

            assertTrue("get accepted it, got $onGet", onGet is IllegalArgumentException)
            assertTrue("set accepted it, got $onSet", onSet is IllegalArgumentException)
            assertTrue("remove accepted it, got $onRemove", onRemove is IllegalArgumentException)
        }

    /** The guard rejects only what is malformed, so a legitimate astral name still works here too. */
    @Test
    fun `a key containing a valid supplementary character is accepted`() =
        runTest(timeout = 5.seconds) {
            val subject = InMemorySecureStorage()

            subject.set("refresh-😀", "secret-value".toByteArray())

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh-😀"))
        }

    /**
     * The copy-on-write guarantee, which is why the map is not a constructor parameter.
     *
     * A consumer that wipes what it passed to [PayabliSecureStorage.set], as the contract tells it to, must not
     * blank the stored value, and one that wipes what it read must not blank it either.
     */
    @Test
    fun `wiping what was passed in or read back does not disturb the store`() =
        runTest(timeout = 5.seconds) {
            val subject = InMemorySecureStorage()
            val written = "secret-value".toByteArray()

            subject.set("refresh", written)
            written.fill(0)
            subject.get("refresh")!!.fill(0)

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
        }

    @Test
    fun `remove deletes the value`() =
        runTest(timeout = 5.seconds) {
            val subject = InMemorySecureStorage()
            subject.set("refresh", "secret-value".toByteArray())

            subject.remove("refresh")

            assertNull(subject.get("refresh"))
        }
}
