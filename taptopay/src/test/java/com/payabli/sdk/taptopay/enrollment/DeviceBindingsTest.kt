package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.network.PayabliJson
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
            assertEquals(listOf("get:$RECORD_ENTRY"), storage.operations.drop(before))
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
    fun `the current entry answers alone, and the older one is left unread`() =
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
