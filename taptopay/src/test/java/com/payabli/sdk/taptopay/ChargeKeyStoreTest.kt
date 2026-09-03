package com.payabli.sdk.taptopay

import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import com.payabli.sdk.taptopay.enrollment.OTHER_ENTRY
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * A charge not taken can be taken again; a charge taken twice cannot be untaken. So a failure that says the
 * key is *gone* mints a fresh one, and a failure that says the store could not be reached *this time* stops
 * the charge.
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
            store.reserve(ENTRY)

            store.settle(ENTRY)

            assertEquals("key-2", store.reserve(ENTRY))
        }

    @Test
    fun `two stores over one backing entry answer with the same key`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A terminal is built per call, so the retry usually reaches a second store. The key has to be
            // the backing entry's rather than either object's.
            val storage = FakeSecureStore()
            val first = storeOver(storage)
            val second = storeOver(storage)

            val reserved = first.reserve(ENTRY)

            assertEquals(reserved, second.reserve(ENTRY))
        }

    @Test
    fun `one entry point's key is not another's`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())

            val one = store.reserve(ENTRY)
            val other = store.reserve(OTHER_ENTRY)

            assertNotEquals(one, other)
            assertEquals("settling one dropped the other", one, store.reserve(ENTRY))
        }

    @Test
    fun `settling one entry point leaves another's alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver(FakeSecureStore())
            val one = store.reserve(ENTRY)
            val other = store.reserve(OTHER_ENTRY)

            store.settle(ENTRY)

            assertEquals(other, store.reserve(OTHER_ENTRY))
            assertNotEquals(one, store.reserve(ENTRY))
        }

    @Test
    fun `a store that cannot be reached stops the charge rather than reserving a second key`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The one that matters. Reading this as "nothing held" would mint a fresh key for an attempt
            // that may already have opened a transaction, and the payer is charged twice.
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
    fun `a key the store says is gone is replaced rather than stopping the charge`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Gone is an answer: nothing is held, so there is no attempt to repeat and a fresh key is right.
            var failing = true
            val storage =
                FakeSecureStore(
                    failWith = { operation, key ->
                        SecureStorageException
                            .KeyInvalidated()
                            .takeIf { failing && operation == "get" && key == CHARGE_KEY_ENTRY }
                    },
                )
            val store = storeOver(storage)

            val reserved = store.reserve(ENTRY)
            failing = false

            assertEquals("key-1", reserved)
        }

    @Test
    fun `a record that will not decode is replaced, and the entry holding it is dropped`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.set(CHARGE_KEY_ENTRY, "not json".toByteArray(Charsets.UTF_8))
            val store = storeOver(storage)

            assertEquals("key-1", store.reserve(ENTRY))
        }

    @Test
    fun `a settle the store refuses does not fail the caller`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The charge already has an outcome the caller is entitled to. A key left behind costs the next
            // charge a suppressed opening; raising here would report a settled payment as a failed one.
            //
            // Reserved before the store starts refusing, because `settle` returns early when nothing is
            // held and would then never reach the failure this asserts on.
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
            store.reserve(ENTRY)
            refusing = true

            store.settle(ENTRY)

            // Still held, since the removal was refused, and the caller was told nothing.
            refusing = false
            assertEquals("the key was forgotten after a refused write", "key-1", store.reserve(ENTRY))
        }
}
