package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.storage.SecureStorageException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * One device serving more than one entry point, and what decides which binding is discarded.
 *
 * The case the whole collection exists for is the last one here: a device that returns to an entry point it
 * enrolled against earlier must find its binding, because registering again retires a device that was active
 * and costs the merchant a fresh code.
 */
class DeviceBindingsTest {
    private fun binding(
        entry: String,
        deviceId: String = "$entry-device",
    ) = AttestedDevice(entry, deviceId, FakeDeviceKey.KEY_IDENTITY)

    @Test
    fun `a binding is read back for its own entry point and for no other`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestedDeviceStore(FakeSecureStore())

            store.write(binding(ENTRY))

            assertEquals("$ENTRY-device", store.read(ENTRY)?.deviceId)
            assertNull(store.read(OTHER_ENTRY))
        }

    @Test
    fun `writing one entry point leaves every other one where it was`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestedDeviceStore(FakeSecureStore())

            store.write(binding(ENTRY))
            store.write(binding(OTHER_ENTRY))

            assertNotNull(store.read(ENTRY))
            assertNotNull(store.read(OTHER_ENTRY))
        }

    @Test
    fun `clearing one entry point leaves every other one where it was`() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = AttestedDeviceStore(FakeSecureStore())
            store.write(binding(ENTRY))
            store.write(binding(OTHER_ENTRY))

            store.clear(ENTRY)

            assertNull(store.read(ENTRY))
            assertNotNull(store.read(OTHER_ENTRY))
        }

    @Test
    fun `re-enrolling an entry point replaces its binding rather than adding a second`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)

            store.write(binding(ENTRY, deviceId = "first"))
            store.write(binding(ENTRY, deviceId = "second"))

            assertEquals("second", store.read(ENTRY)?.deviceId)
            // Two would make which is read depend on where the scan started.
            assertEquals(1, storedBindings(storage).bindings.size)
        }

    @Test
    fun `clearing the last binding removes the entry rather than leaving an empty one`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)
            store.write(binding(ENTRY))

            store.clear(ENTRY)

            assertTrue(storage.isEmpty)
        }

    @Test
    fun `clearing an entry point that holds nothing rewrites nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)
            store.write(binding(ENTRY))
            val before = storage.operations.size

            store.clear(OTHER_ENTRY)

            // A read to look, and no write: the bindings held for other entry points are untouched.
            assertEquals(
                listOf("get:$RECORD_ENTRY", "remove:$LEGACY_RECORD_ENTRY"),
                storage.operations.drop(before),
            )
            assertNotNull(store.read(ENTRY))
        }

    @Test
    fun `the cap is a small number, and there is one`() {
        // The tests either side of this scale themselves to whatever the cap is, so they prove the rule
        // that picks a binding to discard and say nothing about the bound itself. Removing the cap, or
        // raising it to where it no longer bounds anything, passes every one of them. Hence a band: the
        // exact figure is a judgment call and may move, but widening it is a decision about how much
        // merchant identity a device accumulates, and this is what makes it a deliberate one.
        assertTrue("a device should not accumulate a record of many merchants", DeviceBindings.MAX <= 8)
        // Below two, a device serving a second entry point evicts on every switch, which is the defect
        // the collection was added to remove.
        assertTrue("a cap below two restores the defect", DeviceBindings.MAX >= 2)
    }

    @Test
    fun `past the cap the binding nothing has used is the one discarded`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)
            repeat(DeviceBindings.MAX) { store.write(binding("entry-$it")) }

            store.write(binding("one-too-many"))

            assertEquals(DeviceBindings.MAX, storedBindings(storage).bindings.size)
            // The first written is the least recently used, so it is the one that goes.
            assertNull(store.read("entry-0"))
            assertNotNull(store.read("entry-1"))
            assertNotNull(store.read("one-too-many"))
        }

    @Test
    fun `reading a binding saves it from being the next discarded`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)
            repeat(DeviceBindings.MAX) { store.write(binding("entry-$it")) }

            // Used, so it is no longer the coldest even though it was enrolled first.
            assertNotNull(store.read("entry-0"))
            store.write(binding("one-too-many"))

            assertNotNull(store.read("entry-0"))
            assertNull(store.read("entry-1"))
        }

    @Test
    fun `a device returning to an entry point it already enrolled makes no request`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody(), challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(), registerBody(deviceId = "other-device")),
                        RouteScript.ATTEST to listOf(attestBody(), attestBody()),
                    ),
                )

            fixture.enrollment.enroll()
            fixture.enrollmentFor(OTHER_ENTRY).enroll()
            val returning = fixture.enrollment.enroll()

            // The whole point of the collection: coming back finds the binding rather than registering
            // again, which would retire a device that was active and cost the merchant a fresh code.
            assertEquals(EnrollmentOutcome.AlreadyAttested, returning)
            assertEquals(6, fixture.routes.size)
            assertEquals(DEVICE_ID, fixture.storedRecord(ENTRY)?.deviceId)
            assertEquals("other-device", fixture.storedRecord(OTHER_ENTRY)?.deviceId)
        }

    @Test
    fun `an older single-binding record is carried forward and the old entry removed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())
            fixture.seedLegacyRecord()

            // Losing this would run the cold sequence against a device the service still holds as active.
            assertEquals(EnrollmentOutcome.AlreadyAttested, fixture.enrollment.enroll())
            assertEquals(DEVICE_ID, fixture.storedRecord(ENTRY)?.deviceId)
            assertNull(fixture.storage.peek(LEGACY_RECORD_ENTRY))
        }

    @Test
    fun `the current entry answers alone, and the older one is removed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            val store = AttestedDeviceStore(storage)
            store.write(binding(ENTRY))
            // What an upgrade interrupted between its write and its removal would leave behind.
            storage.seed(
                LEGACY_RECORD_ENTRY,
                PayabliJson.format
                    .encodeToString(AttestedDevice.serializer(), binding(OTHER_ENTRY))
                    .encodeToByteArray(),
            )

            // Restoring from it would bring back a binding discarded since the upgrade.
            assertNull(store.read(OTHER_ENTRY))
            assertNotNull(store.read(ENTRY))
            // And it does not stay: unread is not enough, because it names a merchant and would sit there
            // for as long as the app is installed.
            assertNull(storage.peek(LEGACY_RECORD_ENTRY))
        }

    @Test
    fun `an older entry that cannot be removed does not fail the read`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(
                    failWith = FakeSecureStore.failing("remove", SecureStorageException.StorageUnavailable()),
                )
            AttestedDeviceStore(storage).write(binding(ENTRY))

            // The migration wrote a sound binding, and the read has its answer. Raising here would turn
            // that into a failed read and send the caller through a cold sequence it does not need.
            assertEquals("$ENTRY-device", AttestedDeviceStore(storage).read(ENTRY)?.deviceId)
        }

    @Test
    fun `an older record that cannot be decoded reads as nothing and is dropped`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage = FakeSecureStore()
            storage.seed(LEGACY_RECORD_ENTRY, "not json at all".encodeToByteArray())

            assertNull(AttestedDeviceStore(storage).read(ENTRY))
            assertTrue(storage.isEmpty)
        }

    @Test
    fun `an older record is never decoded by the current serializer`() {
        val legacy =
            PayabliJson.format.encodeToString(
                AttestedDevice.serializer(),
                AttestedDevice(ENTRY, DEVICE_ID, FakeDeviceKey.KEY_IDENTITY),
            )

        val decoded =
            runCatching {
                PayabliJson.format.decodeFromString(DeviceBindings.serializer(), legacy)
            }

        // The decoder ignores keys it does not recognize, so a defaulted list here would decode this
        // cleanly and report a device holding a binding as holding none. Two entry names keep the two
        // shapes apart; no default is what makes the mistake impossible rather than merely avoided.
        assertTrue(decoded.exceptionOrNull() is SerializationException)
        assertNull(decoded.getOrNull())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `two stores over one backing entry do not lose each other's bindings`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Parked after its read, so it holds a value taken before the other store's write. Parking it
            // before the read instead proves nothing: it would resume and see that write, which is how this
            // test passed against the defect it was written for.
            val held = CompletableDeferred<Unit>()
            val storage = FakeSecureStore(afterFirstReadGate = { held.await() })
            val first = AttestedDeviceStore(storage)
            val second = AttestedDeviceStore(storage)

            val writingFirst = launch(UnconfinedTestDispatcher(testScheduler)) { first.write(binding(ENTRY)) }
            val writingSecond =
                launch(UnconfinedTestDispatcher(testScheduler)) { second.write(binding(OTHER_ENTRY)) }
            held.complete(Unit)
            writingFirst.join()
            writingSecond.join()

            // Writing one binding means reading them all and writing them back, so a lock held per object
            // rather than per backing entry lets the later write land on a collection read before the
            // earlier one existed, and the earlier binding is gone with nothing raised.
            assertNotNull(first.read(ENTRY))
            assertNotNull(first.read(OTHER_ENTRY))
        }

    @Test
    fun `the storage entry names are the ones installed devices already carry`() =
        runTest(timeout = TEST_TIMEOUT) {
            val current = FakeSecureStore()
            AttestedDeviceStore(current).write(binding(ENTRY))

            val older = FakeSecureStore()
            older.seed(
                "com.payabli.sdk.taptopay.device.v1",
                PayabliJson.format
                    .encodeToString(AttestedDevice.serializer(), binding(ENTRY))
                    .encodeToByteArray(),
            )

            // Renaming either is a migration and not a refactor: an installed device looks under these
            // exact names and finds its binding under no other. The literals here and the ones the fixture
            // seeds with are a second, independent statement of them, held apart from the store's own on
            // purpose. Pointing the tests at the store's constants instead would make a rename invisible,
            // since every test would follow it and the migration cases would go on passing under names
            // their own titles no longer describe.
            assertNotNull("the current entry name moved", current.peek("com.payabli.sdk.taptopay.device.v2"))
            assertNotNull("the older entry name moved", AttestedDeviceStore(older).read(ENTRY))
        }

    @Test
    fun `a carry-forward that cannot be written still answers, and keeps the older entry`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(FakeSecureStore.failing("set", SecureStorageException.StorageUnavailable()))
            storage.seed(
                LEGACY_RECORD_ENTRY,
                PayabliJson.format
                    .encodeToString(AttestedDevice.serializer(), binding(ENTRY))
                    .encodeToByteArray(),
            )

            // The older record decoded, so this device holds a usable binding. Raising over a write would
            // report a record that was read perfectly well as a store that could not be read.
            assertEquals("$ENTRY-device", AttestedDeviceStore(storage).read(ENTRY)?.deviceId)
            // And the older entry is the only copy until the write lands, so removing it here would lose
            // the binding outright.
            assertNotNull(storage.peek(LEGACY_RECORD_ENTRY))
            assertNull(storage.peek(RECORD_ENTRY))
        }

    @Test
    fun `a removal that keeps failing is attempted once, not on every read`() =
        runTest(timeout = TEST_TIMEOUT) {
            val storage =
                FakeSecureStore(FakeSecureStore.failing("remove", SecureStorageException.StorageUnavailable()))
            val store = AttestedDeviceStore(storage)
            store.write(binding(ENTRY))
            val before = storage.operations.size

            repeat(3) { store.read(ENTRY) }

            // This is only reached once the current entry has decoded, so the store is answering reads and a
            // removal failing under those conditions is not a transient the next call clears. Repeating it
            // would record itself every time and change nothing.
            assertEquals(1, storage.operations.drop(before).count { it.startsWith("remove:") })
        }

    @Test
    fun `the collection never prints the entry points it holds`() {
        val printed = DeviceBindings(listOf(binding(ENTRY), binding(OTHER_ENTRY))).toString()

        assertFalse(printed.contains(ENTRY))
        assertFalse(printed.contains(OTHER_ENTRY))
        assertEquals("DeviceBindings(size=2)", printed)
    }

    private fun storedBindings(storage: FakeSecureStore): DeviceBindings =
        PayabliJson.format.decodeFromString(
            DeviceBindings.serializer(),
            storage.peek(RECORD_ENTRY)!!.decodeToString(),
        )
}
