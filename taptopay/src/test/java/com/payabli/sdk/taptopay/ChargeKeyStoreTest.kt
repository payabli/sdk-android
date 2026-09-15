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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val CHARGE_KEY_ENTRY = "com.payabli.sdk.taptopay.chargekeys.v2"

private const val PREVIOUS_CHARGE_KEY_ENTRY = "com.payabli.sdk.taptopay.chargekeys.v1"

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

    /** The settled markers are process state, so one test's outlive it unless they are dropped. */
    @Before
    fun forgetSettledMarkers() = ChargeKeyStore.forgetSettled()

    private val clock = AtomicLong(1_700_000_000_000)

    private fun storeOver(storage: FakeSecureStore) =
        ChargeKeyStore(
            storage,
            newKey = { "key-${minted.incrementAndGet()}" },
            nowMillis = clock::get,
            logger = logger,
        )

    @Test
    fun `an unsettled charge keeps the key it reserved`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            assertEquals("key-1", store.reserve(ENTRY).key)
            assertEquals("key-1", store.reserve(ENTRY).key)
        }

    /** A caller cannot tell a resend from a first send by the key alone, and what it does with a refusal
     *  depends on which it was. */
    @Test
    fun `a reservation says whether the key was already held`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            assertFalse(store.reserve(ENTRY).reused)
            assertTrue(store.reserve(ENTRY).reused)
        }

    @Test
    fun `a settled charge leaves the next one to reserve its own`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            val first = store.reserve(ENTRY).key

            store.settle(ENTRY, first)

            assertEquals("key-2", store.reserve(ENTRY).key)
        }

    @Test
    fun `settling a key that has been superseded removes nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Two terminals for one entry point hold separate charge locks, so a charge can finish after
            // another has reserved in its place. Removing whatever is held would drop an attempt that is
            // still in flight, and its retry would name a new one.
            val storage = FakeSecureStore()
            val store = storeOver(storage)
            val stale = store.reserve(ENTRY).key
            store.settle(ENTRY, stale)
            val current = store.reserve(ENTRY).key

            store.settle(ENTRY, stale)

            assertEquals("the in-flight attempt was dropped by a stale settle", current, store.reserve(ENTRY).key)
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

            val firstKey = async { first.reserve(ENTRY).key }
            entered.await()
            val secondKey = async { second.reserve(ENTRY).key }
            release.complete(Unit)

            assertEquals(firstKey.await(), secondKey.await())
        }

    @Test
    fun `one entry point's key is not another's`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            val one = store.reserve(ENTRY).key
            val other = store.reserve(OTHER_ENTRY).key

            assertNotEquals(one, other)
            assertEquals(one, store.reserve(ENTRY).key)
        }

    @Test
    fun `settling one entry point leaves another's alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            val one = store.reserve(ENTRY).key
            val other = store.reserve(OTHER_ENTRY).key

            store.settle(ENTRY, one)

            assertEquals(other, store.reserve(OTHER_ENTRY).key)
            assertNotEquals(one, store.reserve(ENTRY).key)
        }

    /**
     * A charge that is over does not go on naming itself, even when its record could not be removed.
     *
     * `settle` never fails its caller, so the key stays in storage. Resending it opens nothing, the refusal
     * reads exactly like the one a live attempt earns, and every later charge for this entry point sends it
     * again: the entry point stops being able to take a card at all.
     */
    @Test
    fun `a settled charge whose key could not be forgotten is not resent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(
                    failWith = { operation, _ ->
                        SecureStorageException.StorageUnavailable().takeIf { operation == "remove" }
                    },
                )
            val store = storeOver(storage)

            assertEquals("key-1", store.reserve(ENTRY).key)
            store.settle(ENTRY, "key-1")

            assertEquals("key-2", store.reserve(ENTRY).key)
        }

    /** What replaces it is an ordinary unsettled attempt, so the next charge resends it as it would any. */
    @Test
    fun `a key minted after a cleanup that failed is held like any other`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(
                    failWith = { operation, _ ->
                        SecureStorageException.StorageUnavailable().takeIf { operation == "remove" }
                    },
                )
            val store = storeOver(storage)
            store.reserve(ENTRY)
            store.settle(ENTRY, "key-1")

            assertEquals("key-2", store.reserve(ENTRY).key)
            assertTrue("the replacement was not held", store.reserve(ENTRY).reused)
            assertEquals("key-2", store.reserve(ENTRY).key)
        }

    /**
     * Past the window the key is not resent, which is what keeps the bad path out of reach.
     *
     * A key the service no longer recognises is not refused: the opening it carries is executed exactly as a
     * first send would be. So a charge resending one opens a second transaction while believing itself
     * protected, and reports what that second tap did as though it answered for the first.
     */
    @Test
    fun `a key the service would no longer recognise is not resent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            assertEquals("key-1", store.reserve(ENTRY).key)

            clock.addAndGet(TimeUnit.MINUTES.toMillis(3))

            assertEquals("key-2", store.reserve(ENTRY).key)
            assertFalse("an expired key was reported as held", store.reserve(ENTRY).key == "key-1")
        }

    /** Inside it nothing changes: the refusal a resend earns is the only protection on this path. */
    @Test
    fun `a key inside the window is still resent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            store.reserve(ENTRY)

            clock.addAndGet(TimeUnit.MINUTES.toMillis(3) - 1)

            assertEquals("key-1", store.reserve(ENTRY).key)
            assertTrue(store.reserve(ENTRY).reused)
        }

    /**
     * A clock that moved backwards cannot make a record permanent.
     *
     * The stamp is read clamped into the window, so one in the future counts as reserved now and expires a
     * window later rather than never.
     */
    @Test
    fun `a reservation stamped in the future expires a window after it is read`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            store.reserve(ENTRY)
            clock.addAndGet(-TimeUnit.DAYS.toMillis(30))

            assertEquals("a future stamp was read as expired", "key-1", store.reserve(ENTRY).key)

            clock.addAndGet(TimeUnit.MINUTES.toMillis(3))
            assertEquals("a future stamp never expired", "key-2", store.reserve(ENTRY).key)
        }

    /**
     * A device upgrading keeps the key a charge in flight is holding.
     *
     * The previous record cannot decode into the current shape, and a record that will not decode is what
     * stops a charge until its transactions are resolved outside the app. Every device holding a key would
     * meet that at upgrade.
     */
    @Test
    fun `a record written before reservations were stamped is carried over`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.set(
                PREVIOUS_CHARGE_KEY_ENTRY,
                """{"attempts":[{"entry":"$ENTRY","key":"carried-key"}]}""".toByteArray(),
            )
            val store = storeOver(storage)

            val reserved = store.reserve(ENTRY)

            assertEquals("carried-key", reserved.key)
            assertTrue("the carried key was not recognised as held", reserved.reused)
            assertNull("the previous record was left behind", storage.get(PREVIOUS_CHARGE_KEY_ENTRY))
        }

    /** And it is bounded from the moment it is carried, rather than being held forever. */
    @Test
    fun `a carried key expires like any other`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.set(
                PREVIOUS_CHARGE_KEY_ENTRY,
                """{"attempts":[{"entry":"$ENTRY","key":"carried-key"}]}""".toByteArray(),
            )
            val store = storeOver(storage)
            store.reserve(ENTRY)

            clock.addAndGet(TimeUnit.MINUTES.toMillis(3))

            assertEquals("key-1", store.reserve(ENTRY).key)
        }

    /**
     * The cap refuses a charge to protect the records it already holds, so it counts only the live ones.
     *
     * An expired record names a key the service no longer knows. Counting it turns the refusal into one
     * that protects nothing and stops a merchant charging for no reason.
     */
    @Test
    fun `the cap counts only the charges still in doubt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            repeat(4) { store.reserve("entry-$it") }

            clock.addAndGet(TimeUnit.MINUTES.toMillis(3))

            assertEquals("key-5", store.reserve("entry-4").key)
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

            val failure = runCatching { store.reserve(ENTRY).key }.exceptionOrNull()

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

            val failure = runCatching { store.reserve(ENTRY).key }.exceptionOrNull()

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

            val failure = runCatching { store.reserve(ENTRY).key }.exceptionOrNull()

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

            assertEquals("key-1", store.reserve(ENTRY).key)
        }

    @Test
    fun `a full store refuses a new entry point rather than evicting an unresolved one`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Every record names a charge whose outcome is still in doubt, so dropping the coldest to admit
            // a new one loses the only thing that would recognise its repeat.
            val store = storeOver(FakeSecureStore())
            val first = store.reserve("entry-1").key
            repeat(ChargeAttempts.MAX - 1) { store.reserve("entry-${it + 2}") }

            val failure = runCatching { store.reserve("one-too-many") }.exceptionOrNull()

            assertTrue("$failure", failure is ChargeKeyStoreFullException)
            assertEquals("the oldest unresolved attempt was evicted", first, store.reserve("entry-1").key)
        }

    @Test
    fun `a settle the store refuses does not fail the caller`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The charge already has an outcome the caller is entitled to, so raising here would report a
            // settled payment as a failed one. It is reported to the log instead. What the next charge
            // does with the key left behind is the subject of its own test.
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
            val reserved = store.reserve(ENTRY).key
            refusing = true

            store.settle(ENTRY, reserved)

            assertTrue(
                "a refused cleanup was not reported",
                logger.records.any { it.message.contains("could not be forgotten") },
            )
        }

    @Test
    fun `a settle over a record that will not decode does not fail the caller`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The other way `settle` reaches a failure, and the more expensive one. `load` refuses to answer
            // empty for a record it cannot read, so it raises, and by this line the charge has been closed
            // at the service. Letting that escape hands the caller a failure for a payment the processor
            // completed, and the retry it invites is the second sale this whole class exists to prevent.
            val storage = FakeSecureStore()
            storage.set(CHARGE_KEY_ENTRY, "not json".toByteArray(Charsets.UTF_8))
            val store = storeOver(storage)

            val failure = runCatching { store.settle(ENTRY, "key-1") }.exceptionOrNull()

            assertNull("$failure", failure)
            assertTrue("the unreadable record was removed", storage.get(CHARGE_KEY_ENTRY) != null)
        }
}
