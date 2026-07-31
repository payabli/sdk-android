package com.payabli.sdk.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Concurrent writers must not lose entries, because the shipping store serialises and so must this.
     *
     * A real dispatcher, not `Unconfined`, which runs each `async` to completion in turn so nothing interleaves.
     * Mirrors `FileSecureStorageTest`'s own concurrency test: a fixture that is less safe than what it stands in for
     * makes a consumer's concurrency test flake for a reason production does not have.
     *
     * **A weak detector, and worth saying so.** With the lock removed this catches the defect about 1 run in 5,
     * measured, and raising the writers from 10 to 500 did not change that: the critical section is a single map
     * assignment, so the window for a lost update stays tiny however many callers pile into it. Contention is the
     * only lever available, there being no cipher here to widen the section, and it does not work.
     *
     * The lock is therefore justified by the contract rather than by this test: the shipping store serialises every
     * operation, so a fixture standing in for it must too. What this test does reliably is prove the fixture works
     * *with* the lock, which is not nothing, and it would catch a gross regression such as dropping the copy.
     */
    @Test
    fun `concurrent writes do not lose values`() =
        runTest(timeout = 30.seconds) {
            val subject = InMemorySecureStorage()

            (1..WRITERS).map { async(Dispatchers.IO) { subject.set("key-$it", "value-$it".toByteArray()) } }.awaitAll()

            (1..WRITERS).forEach {
                assertArrayEquals("value-$it was dropped", "value-$it".toByteArray(), subject.get("key-$it"))
            }
        }

    private companion object {
        /** Ten, matching the store's own concurrency test. 500 was measured and detected no better. */
        const val WRITERS = 10
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
