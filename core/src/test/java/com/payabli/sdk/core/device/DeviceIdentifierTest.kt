package com.payabli.sdk.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes this value an identifier rather than a number that changes.
 *
 * Every property below is one a device registration and a telemetry stream both depend on, and every one of
 * them fails silently if it breaks: the device registers again as a stranger, or its events stop grouping.
 */
class DeviceIdentifierTest {
    @Test
    fun theSameDeviceAlwaysDerivesTheSameIdentifier() {
        assertEquals(derive(), derive())
    }

    @Test
    fun theShapeIsThirtyTwoLowercaseHex() {
        val id = derive()

        assertEquals(32, id.length)
        assertTrue(id, Regex("^[a-f0-9]{32}$").matches(id))
    }

    /** The platform scopes its value more widely than one app, so two apps must not read as one device. */
    @Test
    fun twoApplicationsOnOneDeviceAreTwoIdentifiers() {
        assertNotEquals(
            derive(hostPackageName = "com.first.app"),
            derive(hostPackageName = "com.second.app"),
        )
    }

    @Test
    fun twoDevicesUnderOneApplicationAreTwoIdentifiers() {
        assertNotEquals(derive(installationId = "aaaa"), derive(installationId = "bbbb"))
    }

    /**
     * The raw platform value never leaves, which is the whole point of the digest.
     *
     * A change that passed the value through, or prefixed it, would keep every other test here green.
     */
    @Test
    fun theRawPlatformValueIsNotRecoverableFromTheResult() {
        val installationId = "0123456789abcdef"

        val id = derive(installationId = installationId)

        assertTrue(id, !id.contains(installationId))
    }

    /**
     * A blank in, a blank out — never a substitute.
     *
     * An identifier invented where the platform offered none registers a new device on every call and
     * correlates nothing. The sibling SDK does exactly that today, which is why it re-registers.
     */
    @Test
    fun nothingFromThePlatformYieldsNothingRatherThanAnInvention() {
        assertEquals("", derive(installationId = ""))
        assertEquals("", derive(installationId = "   "))
    }

    /** Changing it re-identifies every device, so it is pinned rather than left to a rename. */
    @Test
    fun theSdkIdentifierIsPartOfTheDigest() {
        assertNotEquals(derive(sdkIdentifier = "com.payabli.sdk"), derive(sdkIdentifier = "com.payabli.other"))
    }

    private fun derive(
        installationId: String = "an-installation-id",
        hostPackageName: String = "com.example.host",
        sdkIdentifier: String = "com.payabli.sdk",
    ): String = DeviceIdentifier.derive(installationId, hostPackageName, sdkIdentifier)
}
