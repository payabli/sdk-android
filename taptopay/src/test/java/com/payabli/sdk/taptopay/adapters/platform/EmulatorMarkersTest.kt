package com.payabli.sdk.taptopay.adapters.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which builds the support signal calls emulated.
 *
 * Pure, so every value is passed in: reading `Build` here would answer for whichever machine ran the test
 * and assert nothing.
 */
class EmulatorMarkersTest {
    private fun handset() =
        mapOf(
            "brand" to "google",
            "device" to "shiba",
            "fingerprint" to "google/shiba/shiba:16/BP31.250610.004/13400364:user/release-keys",
            "manufacturer" to "Google",
            "model" to "Pixel 8",
            "product" to "shiba",
            "hardware" to "shiba",
        )

    private fun looksEmulatedFrom(facts: Map<String, String>) =
        looksEmulated(
            brand = facts.getValue("brand"),
            device = facts.getValue("device"),
            fingerprint = facts.getValue("fingerprint"),
            manufacturer = facts.getValue("manufacturer"),
            model = facts.getValue("model"),
            product = facts.getValue("product"),
            hardware = facts.getValue("hardware"),
        )

    @Test
    fun `a handset is not emulated`() {
        assertFalse(looksEmulatedFrom(handset()))
    }

    @Test
    fun `each field is enough on its own`() {
        // One field at a time, because a marker that only matches when several agree would pass a test
        // that spoiled all of them and miss the image that carries one.
        for (field in handset().keys) {
            assertTrue(
                "a marker in $field was not read",
                looksEmulatedFrom(handset() + (field to "generic")),
            )
        }
    }

    @Test
    fun `the images this SDK is actually run on are recognised`() {
        val images =
            listOf(
                "sdk_gphone64_arm64",
                "sdk_phone64_arm64",
                "emu64a",
                "goldfish",
                "ranchu",
                "Android SDK built for arm64",
                "Genymotion",
                "google_sdk",
            )
        for (image in images) {
            assertTrue(image, looksEmulatedFrom(handset() + ("product" to image)))
        }
    }

    @Test
    fun `a property Android could not read is not an emulator`() {
        // `unknown` is Android's own substitute for a build property it cannot read. Matching it reported a
        // handset with one missing property as unable to take payments, and `false` is the half of this
        // answer a host is told to rely on.
        for (field in handset().keys) {
            assertFalse(
                "a missing $field was read as an emulator",
                looksEmulatedFrom(handset() + (field to "unknown")),
            )
        }
    }

    @Test
    fun `a real brand containing no marker stays a handset`() {
        // The check runs on every host that links this module, so a false positive turns card-present off
        // for a device that can take payments. These are the names most likely to collide.
        for (brand in listOf("samsung", "motorola", "oneplus", "Xiaomi", "HMD Global")) {
            assertFalse(brand, looksEmulatedFrom(handset() + ("brand" to brand)))
        }
    }
}
