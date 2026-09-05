package com.payabli.sdk.taptopay

import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import com.payabli.sdk.taptopay.enrollment.OTHER_ENTRY
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val CHARGE_KEY_ENTRY = "com.payabli.sdk.taptopay.chargekeys.v1"

/**
 * What the store answers when it can and cannot read what it holds.
 *
 * A charge not taken can be taken again; a charge taken twice cannot be untaken. So only an entry that is
 * genuinely absent answers "nothing held"; every other outcome stops the charge instead of letting a fresh
 * key be minted over an attempt whose fate is unknown.
 */
class ChargeKeyStoreTest {
    private val logger = RecordingSdkLogger()
    private val minted = AtomicInteger()

    private fun storeOver(storage: FakeSecureStore) =
        ChargeKeyStore(storage, newKey = { "key-${minted.incrementAndGet()}" }, logger = logger)

    @Test
    fun `an unsettled charge keeps the key it reserved`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            assertEquals("key-1", store.reserve(ENTRY))
            assertEquals("key-1", store.reserve(ENTRY))
        }

    @Test
    fun `a settled charge leaves the next one to reserve its own`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            val first = store.reserve(ENTRY)

            store.settle(ENTRY, first)

            assertEquals("key-2", store.reserve(ENTRY))
        }

    @Test
    fun `settling a key that has been superseded removes nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Two terminals for one entry point hold separate charge locks, so a charge can finish after
            // another has reserved in its place. Removing whatever is held would drop an attempt that is
            // still in flight, and its retry would name a new one.
            val storage = FakeSecureStore()
            val store = storeOver(storage)
            val stale = store.reserve(ENTRY)
            store.settle(ENTRY, stale)
            val current = store.reserve(ENTRY)

            store.settle(ENTRY, stale)

            assertEquals("the in-flight attempt was dropped by a stale settle", current, store.reserve(ENTRY))
        }

    @Test
    fun `two stores over one backing entry answer with the same key`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A terminal is built per call, so the retry usually reaches a second store. The key has to be
            // the backing entry's rather than either object's.
            //
            // The two reservations overlap, which is the whole point: run in sequence the first write has
            // already landed and a mutex held per instance passes just as well. Here the first is parked
            // after its read, so a lock that is not shared lets the second read the same empty record and
            // mint a second key.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val storage =
                FakeSecureStore(
                    afterFirstReadGate = {
                        entered.complete(Unit)
                        release.await()
                    },
                )
            val first = storeOver(storage)
            val second = storeOver(storage)

            val firstKey = async { first.reserve(ENTRY) }
            entered.await()
            val secondKey = async { second.reserve(ENTRY) }
            release.complete(Unit)

            assertEquals(firstKey.await(), secondKey.await())
        }

    @Test
    fun `one entry point's key is not another's`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            val one = store.reserve(ENTRY)
            val other = store.reserve(OTHER_ENTRY)

            assertNotEquals(one, other)
            assertEquals(one, store.reserve(ENTRY))
        }

    @Test
    fun `settling one entry point leaves another's alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            val one = store.reserve(ENTRY)
            val other = store.reserve(OTHER_ENTRY)

            store.settle(ENTRY, one)

            assertEquals(other, store.reserve(OTHER_ENTRY))
            assertNotEquals(one, store.reserve(ENTRY))
        }

    @Test
    fun `a store that cannot be reached stops the charge rather than reserving a second key`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Reading this as "nothing held" would mint a fresh key for an attempt that may already have
            // opened a transaction, and the payer is charged twice.
            val storage =
                FakeSecureStore(
                    failWith = { operation, key ->
                        SecureStorageException
                            .CryptoUnavailable()
                            .takeIf { operation == "get" && key == CHARGE_KEY_ENTRY }
                    },
                )
            val store = storeOver(storage)

            val failure = runCatching { store.reserve(ENTRY) }.exceptionOrNull()

            assertTrue("$failure", failure is SecureStorageException.CryptoUnavailable)
            assertEquals("a key was minted for an attempt that may already exist", 0, minted.get())
        }

    @Test
    fun `a key the store says is gone stops the charge, because gone is not the same as never held`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A key lost after a captured sale whose close failed looks exactly like a device that has
            // never charged. Only an absent entry is evidence of the second.
            val storage =
                FakeSecureStore(
                    failWith = { operation, key ->
                        SecureStorageException
                            .KeyInvalidated()
                            .takeIf { operation == "get" && key == CHARGE_KEY_ENTRY }
                    },
                )
            val store = storeOver(storage)

            val failure = runCatching { store.reserve(ENTRY) }.exceptionOrNull()

            assertTrue("$failure", failure is SecureStorageException.KeyInvalidated)
            assertEquals(0, minted.get())
        }

    @Test
    fun `a record that will not decode stops the charge, and is left where it is`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Removing it would make the loss permanent and hand the next charge the empty answer this
            // refuses to give.
            val storage = FakeSecureStore()
            storage.set(CHARGE_KEY_ENTRY, "not json".toByteArray(Charsets.UTF_8))
            val store = storeOver(storage)

            val failure = runCatching { store.reserve(ENTRY) }.exceptionOrNull()

            assertTrue("$failure", failure is ChargeKeyUnreadableException)
            assertEquals(0, minted.get())
            assertTrue("the unreadable record was removed", storage.get(CHARGE_KEY_ENTRY) != null)
        }

    @Test
    fun `what would not decode is not carried out on the cause chain`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The record holds entry points and idempotency keys, and kotlinx appends the input it choked
            // on to its message. This failure reaches a host as TapToPayException.cause.cause, so the whole
            // chain is walked rather than the top of it.
            val storage = FakeSecureStore()
            storage.set(
                CHARGE_KEY_ENTRY,
                """{"attempts":[{"entry":"tell-tale-entry","key":""".toByteArray(Charsets.UTF_8),
            )

            val failure = runCatching { storeOver(storage).reserve(ENTRY) }.exceptionOrNull()

            assertTrue("$failure", failure is ChargeKeyUnreadableException)
            generateSequence(failure) { it.cause }.forEach { link ->
                assertFalse(
                    "the rejected record reached ${link.javaClass.name}: ${link.message}",
                    link.message.orEmpty().contains("tell-tale-entry"),
                )
            }
        }

    @Test
    fun `an absent entry is the one thing that means nothing is held`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            assertEquals("key-1", store.reserve(ENTRY))
        }

    @Test
    fun `a full store refuses a new entry point rather than evicting an unresolved one`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Every record names a charge whose outcome is still in doubt, so dropping the coldest to admit
            // a new one loses the only thing that would recognise its repeat.
            val store = storeOver(FakeSecureStore())
            val first = store.reserve("entry-1")
            repeat(ChargeAttempts.MAX - 1) { store.reserve("entry-${it + 2}") }

            val failure = runCatching { store.reserve("one-too-many") }.exceptionOrNull()

            assertTrue("$failure", failure is ChargeKeyStoreFullException)
            assertEquals("the oldest unresolved attempt was evicted", first, store.reserve("entry-1"))
        }

    @Test
    fun `a settle the store refuses does not fail the caller`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The charge already has an outcome the caller is entitled to. A key left behind costs the next
            // charge a suppressed opening; raising here would report a settled payment as a failed one.
            //
            // Reserved before the store starts refusing, because `settle` returns early when the key does
            // not match and would then never reach the failure this asserts on.
            var refusing = false
            val storage =
                FakeSecureStore(
                    failWith = { operation, _ ->
                        SecureStorageException
                            .StorageUnavailable()
                            .takeIf { refusing && (operation == "set" || operation == "remove") }
                    },
                )
            val store = storeOver(storage)
            val reserved = store.reserve(ENTRY)
            refusing = true

            store.settle(ENTRY, reserved)

            // Still held, since the removal was refused, and the caller was told nothing.
            refusing = false
            assertEquals("the key was forgotten after a refused write", "key-1", store.reserve(ENTRY))
        }
}
