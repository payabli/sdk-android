package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.attestation.device.RedactedCause
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * How the record is kept, and the distinction the read has to preserve.
 *
 * Two of the store's four failures mean the record is gone and two mean it could not be read right now.
 * Collapsing them would run the cold sequence against a device the service may still hold as active, which
 * retires it and costs the merchant a code — so each subtype gets its own row here, and adding a fifth
 * upstream breaks this instead of falling through an else.
 */
class AttestedDeviceStoreTest {
    @Test
    fun `a record round-trips`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)

            store.write(AttestedDevice(ENTRY, DEVICE_ID, FakeDeviceKey.KEY_IDENTITY))
            val read = store.read(ENTRY)!!

            assertEquals(ENTRY, read.entry)
            assertEquals(DEVICE_ID, read.deviceId)
            assertEquals(FakeDeviceKey.KEY_IDENTITY, read.keyId)
        }

    @Test
    fun `nothing stored reads as nothing, not as a failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            assertNull(AttestedDeviceStore(FakeSecureStore()).read(ENTRY))
        }

    @Test
    fun `a lost key reads as a cold device`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store =
                AttestedDeviceStore(
                    FakeSecureStore(FakeSecureStore.failing("get", SecureStorageException.KeyInvalidated())),
                )

            assertNull(store.read(ENTRY))
        }

    @Test
    fun `an unauthenticated entry reads as a cold device`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store =
                AttestedDeviceStore(
                    FakeSecureStore(FakeSecureStore.failing("get", SecureStorageException.ValueUnreadable())),
                )

            assertNull(store.read(ENTRY))
        }

    @Test
    fun `an unavailable cipher is raised, never read as a cold device`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store =
                AttestedDeviceStore(
                    FakeSecureStore(FakeSecureStore.failing("get", SecureStorageException.CryptoUnavailable())),
                )

            val thrown = runCatching { store.read(ENTRY) }.exceptionOrNull()
            assertEquals(SecureStorageException.CryptoUnavailable::class.java, thrown?.javaClass)
        }

    @Test
    fun `an unavailable file is raised, never read as a cold device`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store =
                AttestedDeviceStore(
                    FakeSecureStore(FakeSecureStore.failing("get", SecureStorageException.StorageUnavailable())),
                )

            val thrown = runCatching { store.read(ENTRY) }.exceptionOrNull()
            assertEquals(SecureStorageException.StorageUnavailable::class.java, thrown?.javaClass)
        }

    @Test
    fun `all four storage failures are handled, and each differently`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Reading is the whole point: two answer null and two raise, so a fifth subtype added upstream
            // reaches neither branch and fails here instead of quietly joining one of them.
            val gone =
                listOf<SecureStorageException>(
                    SecureStorageException.KeyInvalidated(),
                    SecureStorageException.ValueUnreadable(),
                )
            val raised =
                listOf<SecureStorageException>(
                    SecureStorageException.CryptoUnavailable(),
                    SecureStorageException.StorageUnavailable(),
                )

            for (failure in gone) {
                val store =
                    AttestedDeviceStore(
                        FakeSecureStore(FakeSecureStore.failing("get", failure)),
                    )
                assertNull(failure.javaClass.simpleName, store.read(ENTRY))
            }
            for (failure in raised) {
                val store =
                    AttestedDeviceStore(
                        FakeSecureStore(FakeSecureStore.failing("get", failure)),
                    )
                assertEquals(
                    failure.javaClass.simpleName,
                    failure.javaClass,
                    runCatching { store.read(ENTRY) }.exceptionOrNull()?.javaClass,
                )
            }
        }

    @Test
    fun `an undecodable record reads as nothing and is dropped`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.seed(RECORD_ENTRY, "not json at all".encodeToByteArray())
            val store = AttestedDeviceStore(storage)

            assertNull(store.read(ENTRY))
            assertTrue(storage.isEmpty)
        }

    @Test
    fun `an undecodable record is reported without its contents reaching the log`() =
        runTest(timeout = TEST_TIMEOUT) {
            val logger = RecordingSdkLogger()
            val storage = FakeSecureStore()
            // Truncated mid-value, which is what the serializer quotes back in its message.
            storage.seed(RECORD_ENTRY, """{"entry":"$ENTRY","deviceId":"$DEVICE_ID","key""".encodeToByteArray())

            AttestedDeviceStore(storage, logger).read(ENTRY)

            val record = logger.records.single { it.message.contains("stored device identity is gone") }
            val rendered = "${record.throwable}${record.throwable?.message}"
            // By this point the record is decrypted, so the serializer's own message carries it.
            assertFalse(rendered.contains(ENTRY))
            assertFalse(rendered.contains(DEVICE_ID))
            assertEquals(RedactedCause::class.java, record.throwable?.javaClass)
        }

    @Test
    fun `an undecodable record reads as nothing even when it cannot be dropped`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(FakeSecureStore.failing("remove", SecureStorageException.StorageUnavailable()))
            storage.seed(RECORD_ENTRY, "not json at all".encodeToByteArray())

            // The record is gone whether or not the entry holding it can be dropped. Raising instead would
            // report it as a store that could not be read this time, and the next attempt decodes the same
            // bytes and raises again, so the entry is never dropped on any of them.
            assertNull(AttestedDeviceStore(storage).read(ENTRY))
        }

    @Test
    fun `a record missing a field reads as nothing and is dropped`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.seed(RECORD_ENTRY, """{"entry":"$ENTRY","deviceId":"$DEVICE_ID"}""".encodeToByteArray())
            val store = AttestedDeviceStore(storage)

            assertNull(store.read(ENTRY))
            assertTrue(storage.isEmpty)
        }

    @Test
    fun `a failed write leaves nothing half stored`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(FakeSecureStore.failing("set", SecureStorageException.StorageUnavailable()))
            val store = AttestedDeviceStore(storage)

            runCatching { store.write(AttestedDevice(ENTRY, DEVICE_ID, FakeDeviceKey.KEY_IDENTITY)) }

            // One write, so there is no partial state to compensate for — this asserts that shape holds.
            assertTrue(storage.isEmpty)
        }

    @Test
    fun `clearing removes the record and nothing else`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.seed("com.payabli.sdk.core.some.other.entry", "another consumer's value".encodeToByteArray())
            val store = AttestedDeviceStore(storage)
            store.write(AttestedDevice(ENTRY, DEVICE_ID, FakeDeviceKey.KEY_IDENTITY))

            store.clear(ENTRY)

            assertNull(store.read(ENTRY))
            assertNotNull(storage.peek("com.payabli.sdk.core.some.other.entry"))
        }

    @Test
    fun `the record never prints its identity`() {
        val record = AttestedDevice(ENTRY, DEVICE_ID, FakeDeviceKey.KEY_IDENTITY)

        val printed = record.toString()

        assertFalse(printed.contains(ENTRY))
        assertFalse(printed.contains(DEVICE_ID))
        assertFalse(printed.contains(FakeDeviceKey.KEY_IDENTITY))
        assertEquals("AttestedDevice()", printed)
    }
}
