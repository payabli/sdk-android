package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.InMemorySecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The two names, and the promotion between them.
 *
 * Against the in-memory store, because what is being asserted is the slot bookkeeping rather than
 * encryption at rest. Nothing here needs a device.
 */
class DeviceKeySlotsTest {
    private val storage = InMemorySecureStorage()
    private val subject = DeviceKeySlots(storage)

    private val first = DeviceKeyAliases.newAlias()
    private val second = DeviceKeyAliases.newAlias()

    @Test
    fun `both slots start empty`() =
        runTest(timeout = TEST_TIMEOUT) {
            assertNull(subject.active())
            assertNull(subject.pending())
        }

    @Test
    fun `a pending alias is readable and is not yet active`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)

            // The whole point of the second slot: attesting a key must not make it the signing key until
            // the service has accepted it.
            assertEquals(first, subject.pending())
            assertNull(subject.active())
        }

    @Test
    fun `setting pending twice keeps one key rather than accumulating them`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)
            subject.setPending(second)

            assertEquals(second, subject.pending())
        }

    @Test
    fun `promotion activates the pending alias and clears the slot`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)

            val promotion = subject.promotePending()

            assertNotNull(promotion)
            assertEquals(first, promotion?.activated)
            assertEquals(first, subject.active())
            assertNull("a promoted key is no longer pending", subject.pending())
        }

    @Test
    fun `promotion names the key it displaced so the caller can discard it`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)
            subject.promotePending()
            subject.setPending(second)

            val promotion = subject.promotePending()

            // Without this the old key stays in the key store for the life of the install, and nothing
            // names it, so nothing can ever delete it.
            assertEquals(second, promotion?.activated)
            assertEquals(first, promotion?.replaced)
        }

    @Test
    fun `promoting with nothing pending reports nothing rather than failing`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)
            subject.promotePending()

            // A caller promoting twice is asking a question, not making a mistake: the second call means
            // the attestation it is reacting to was already recorded.
            assertNull(subject.promotePending())
            assertEquals(first, subject.active())
        }

    @Test
    fun `re-promoting the same alias reports no displaced key`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)
            subject.promotePending()
            subject.setPending(first)

            val promotion = subject.promotePending()

            // Naming it as displaced would have the caller delete the key it just activated.
            assertEquals(first, promotion?.activated)
            assertNull(promotion?.replaced)
        }

    @Test
    fun `a stored name from outside this namespace is not returned`() =
        runTest(timeout = TEST_TIMEOUT) {
            storage.set("devicekey.active", "some.other.alias".toByteArray())

            // It would send a caller to a key store entry nothing here minted, which is worse than having
            // no key: the failure would look like a broken key rather than an empty slot.
            assertNull(subject.active())
        }

    @Test
    fun `clearing forgets both names`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.setPending(first)
            subject.promotePending()
            subject.setPending(second)

            subject.clear()

            assertNull(subject.active())
            assertNull(subject.pending())
        }
}
