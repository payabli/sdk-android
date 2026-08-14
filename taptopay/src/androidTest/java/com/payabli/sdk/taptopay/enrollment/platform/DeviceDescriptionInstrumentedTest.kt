package com.payabli.sdk.taptopay.enrollment.platform

import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the platform actually reports, which no unit test can ask. */
@RunWith(AndroidJUnit4::class)
class DeviceDescriptionInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theHardwareIdentifierIsTheSameOnEveryRead() {
        // The property the sibling SDK loses on one branch, which registers a new device per call.
        assertEquals(
            DeviceDescriptionFactory.create(context).hardwareId,
            DeviceDescriptionFactory.create(context).hardwareId,
        )
    }

    @Test
    fun theHardwareIdentifierIsAFixedWidthDigest() {
        val hardwareId = DeviceDescriptionFactory.create(context).hardwareId

        assertEquals(32, hardwareId.length)
        assertTrue(hardwareId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun theHardwareIdentifierIsNotTheRawPlatformValue() {
        val raw =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

        val hardwareId = DeviceDescriptionFactory.create(context).hardwareId

        // The digest has the same lifetime as the raw value and none of its joinability.
        assertNotEquals(raw, hardwareId)
        assertTrue(raw.isNotBlank())
    }

    @Test
    fun theModelAndOsVersionAreTheBuildValues() {
        val description = DeviceDescriptionFactory.create(context)

        // Equality against each, so a transposed pair fails instead of passing as two non-null strings.
        assertEquals(Build.MODEL, description.model)
        assertEquals(Build.VERSION.RELEASE, description.osVersion)
    }

    @Test
    fun noUserSuppliedNameIsCollected() {
        // The field is optional on the wire, and the only value the platform offers is one the owner typed.
        assertNull(DeviceDescriptionFactory.create(context).deviceName)
    }
}
