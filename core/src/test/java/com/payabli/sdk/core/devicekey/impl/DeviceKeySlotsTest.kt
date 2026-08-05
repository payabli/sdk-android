package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.InMemorySecureStorage
import com.payabli.sdk.core.storage.PayabliSecureStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/** Long enough that a caller which is not blocked would have finished; short enough to stay a test. */
private val BLOCKED_PROBE = 300.milliseconds

/** The name the pending slot is stored under, read directly where the storage layer itself is the subject. */
private const val RAW_PENDING = "devicekey.pending"

/**
 * The two names, and every path that could drop one.
 *
 * A dropped name strands a key: the private half stays in the platform store and nothing can name it for
 * deletion. Most of these assert that a name a caller still needs is readable, rather than that the
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

            assertEquals(first, subject.promotePending())

            assertEquals(first, subject.active())
            assertNull("an attested key is no longer awaiting attestation", subject.pending())
        }

    @Test
    fun `the displaced name is readable before promotion, which is where a caller takes it`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()
            subject.pendingOrNew(second)

            // Promotion does not hand back what it displaced, because a return value is lost whenever the
            // call does not complete. A caller that means to delete this key reads it here first.
            assertEquals(first, subject.active())
            assertEquals(second, subject.promotePending())
            assertEquals(second, subject.active())
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

    /**
     * `runBlocking` and real dispatchers, because this is about two callers genuinely interleaving.
     *
     * The gate makes the collision deterministic. Two coroutines launched together almost never both land
     * inside the read-then-write window, so a test that merely races them passes with the lock removed.
     */
    @Test
    fun `two callers minting at once agree on one alias`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gated = GatedStorage(storage)
                val subject = DeviceKeySlots(gated)

                val first = async(Dispatchers.IO) { subject.pendingOrNew(this@DeviceKeySlotsTest.first) }
                // Held inside the first caller's write, which is the window a second caller must not enter.
                gated.insideWrite.await()
                val second = async(Dispatchers.IO) { subject.pendingOrNew(this@DeviceKeySlotsTest.second) }

                assertNull(
                    "the second caller entered the transition while the first was inside it",
                    withTimeoutOrNull(BLOCKED_PROBE) { second.await() },
                )
                gated.release.complete(Unit)

                // Without serialising, both write and the loser's key is left in the platform store with
                // nothing naming it.
                assertEquals(first.await(), second.await())
                assertEquals(first.await(), subject.pending())
            }
        }

    @Test
    fun `discarding drops both names`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.pendingOrNew(first)
            subject.promotePending()
            subject.pendingOrNew(second)

            subject.discard()

            assertNull(subject.active())
            assertNull(subject.pending())
        }
}

/**
 * Holds the first write open, so the read-then-write window is a place a second caller can be observed
 * arriving rather than a few microseconds nothing collides inside.
 */
private class GatedStorage(
    private val delegate: PayabliSecureStorage,
) : PayabliSecureStorage {
    val insideWrite = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun get(key: String): ByteArray? = delegate.get(key)

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        if (insideWrite.complete(Unit)) release.await()
        delegate.set(key, value)
    }

    override suspend fun remove(key: String) = delegate.remove(key)
}
