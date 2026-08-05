package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.InMemorySecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/** The name the pending slot is stored under, read directly where the storage layer itself is the subject. */
private const val RAW_PENDING = "devicekey.pending"

/**
 * The two names, and every path that could drop one.
 *
 * A dropped name strands a key: the private half stays in the platform store and nothing can name it for
 * deletion. Most of these assert that a name is either kept or handed back, rather than asserting the
 * bookkeeping looks tidy.
 *
 * Against the in-memory store, because what is asserted is the bookkeeping rather than encryption at rest.
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
    fun `a candidate becomes pending and is not yet active`() =
        runTest(timeout = TEST_TIMEOUT) {
            assertEquals(first, subject.pendingOrNew(first))

            // The whole point of the second slot: attesting a key must not make it the signing key until the
            // service has accepted it.
            assertEquals(first, subject.pending())
            assertNull(subject.active())
        }

    @Test
    fun `a second candidate does not displace the key already awaiting attestation`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)

            // Returning `second` would leave `first`'s key in the store with nothing naming it, once per
            // retry. Reuse is what makes a retry attest the key it already minted.
            assertEquals(first, subject.pendingOrNew(second))
            assertEquals(first, subject.pending())
        }

    @Test
    fun `promotion activates the pending alias and stops reporting it as pending`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)

            val promotion = subject.promotePending()

            assertNotNull(promotion)
            assertEquals(first, promotion?.activated)
            assertEquals(first, subject.active())
            assertNull("an attested key is no longer awaiting attestation", subject.pending())
        }

    @Test
    fun `promotion names the key it displaced so the caller can discard it`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()
            subject.pendingOrNew(second)

            val promotion = subject.promotePending()

            // Without this the displaced key stays in the store for the life of the install, and nothing
            // names it, so nothing can ever delete it.
            assertEquals(second, promotion?.activated)
            assertEquals(first, promotion?.replaced)
        }

    @Test
    fun `promotion leaves the pending name in place rather than taking a second write`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()

            // The design this rests on. Clearing the name would need a second write, and a failure between
            // the two loses the displaced alias: the retry reads the promoted alias as already active,
            // reports nothing displaced, and strands the key it replaced. Equal-to-active is how an attested
            // key reads instead, so there is nothing to clear.
            assertNotNull("the pending name should still be stored", storage.get(RAW_PENDING))
            assertNull("and must not be reported as awaiting attestation", subject.pending())
        }

    @Test
    fun `promoting with nothing pending reports nothing rather than failing`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()

            // A caller promoting twice is reacting to an acceptance that was already recorded.
            assertNull(subject.promotePending())
            assertEquals(first, subject.active())
        }

    @Test
    fun `a candidate offered after promotion becomes pending rather than being lost`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()

            // A rotation started right after an acceptance. Were promotion to clear the pending name, this
            // candidate could be erased by that clear and its key stranded.
            assertEquals(second, subject.pendingOrNew(second))
            assertEquals(second, subject.pending())
            assertEquals("the active key keeps signing until the new one is attested", first, subject.active())
        }

    @Test
    fun `a stored name from outside this namespace is not returned`() =
        runTest(timeout = TEST_TIMEOUT) {
            storage.set("devicekey.active", "some.other.alias".toByteArray())

            // It would send a caller to a key store entry nothing here minted, which is worse than an empty
            // slot: the failure would look like a broken key rather than a missing one.
            assertNull(subject.active())
        }

    @Test
    fun `forgetting reports both names so neither key is stranded`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()
            subject.pendingOrNew(second)

            val forgotten = subject.forget()

            assertEquals(first, forgotten.active)
            assertEquals(second, forgotten.pending)
            assertNull(subject.active())
            assertNull(subject.pending())
        }
}
