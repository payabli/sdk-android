package com.payabli.sdk.core

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

/**
 * What the target under test can be held to about where a key physically lives.
 *
 * Shared by the manual tiers over the storage key and the device key, which ask the same question of two
 * different keys. Each carried its own copy of the API-aware check below, and two copies of a rule about
 * platform behaviour drift apart.
 */
internal object HardwareKeystoreTarget {
    /**
     * Whether the key store is emulated, in which case nothing here can assert hardware backing.
     *
     * **An emulator advertises the hardware-keystore feature and then produces a software key.** Measured on
     * an API 37 system image: `android.hardware.hardware_keystore` is present at version 400, and a key
     * generated there reports `SECURITY_LEVEL_SOFTWARE`. The feature flag alone therefore cannot gate these
     * tests, and without this check they fail on an emulator with a message that reads as a defect in key
     * creation rather than as a target that cannot answer.
     *
     * `Build.HARDWARE` is the discriminator, measured as `ranchu` on that image against `lynx` and `mt6833`
     * on phones. `goldfish` is the same field on older emulator images and is listed for that reason rather
     * than from a measurement here.
     *
     * A denylist on purpose. An emulator whose name is absent runs the assertions and fails visibly, which a
     * reader can correct; treating every unrecognised target as emulated would silently drop this coverage on
     * every phone instead.
     */
    fun isEmulated(): Boolean = Build.HARDWARE in EMULATED_HARDWARE

    /**
     * Whether this device is **required** to back its keystore with an isolated execution environment.
     *
     * API-aware, because the obvious query is wrong below 31. `FEATURE_HARDWARE_KEYSTORE` was introduced at
     * API 31, so an API 23 to 30 device does not advertise it even when its keystore *is* hardware-backed,
     * and querying it there would fail a correct implementation.
     *
     * Below 31 the signal is `FEATURE_FINGERPRINT`, which exists from API 23, this module's floor. The
     * Compatibility Definition Document requires at `[9.11/H-0-2]` that a device "MUST back up the keystore
     * implementation with an isolated execution environment", and exempts devices launched on earlier
     * versions **unless they declare the `android.hardware.fingerprint` feature flag**. A device declaring
     * fingerprint is therefore not exempt, which makes hardware backing a requirement rather than a hope.
     *
     * The one case left unanswered is a pre-31 device without fingerprint, genuinely exempt, which skips.
     *
     * This answers what the device claims, and [isEmulated] is what says whether the claim can be believed.
     */
    fun requiresHardwareBackedKeystore(): Boolean {
        val features =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            features.hasSystemFeature(HARDWARE_KEYSTORE_FEATURE)
        } else {
            features.hasSystemFeature(FINGERPRINT_FEATURE)
        }
    }

    /**
     * Skips unless this target can be held to an assertion about hardware backing.
     *
     * Two conditions with two different reasons, stated separately so a skipped run says which one applied.
     * Collapsing them into one message would report an emulator as a device that advertises no hardware
     * keystore, which is the opposite of what it does.
     */
    fun assumeHardwareBackingIsAssertable() {
        Assume.assumeFalse(
            "the key store is emulated, so it reports SOFTWARE however correct the code is",
            isEmulated(),
        )
        Assume.assumeTrue(
            "this device advertises no hardware keystore, so there is no hardware backing to assert",
            requiresHardwareBackedKeystore(),
        )
    }

    /** Whether the device offers a secure element, which is a stronger claim than hardware backing. */
    fun hasStrongBox(): Boolean =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .packageManager
            .hasSystemFeature(STRONGBOX_FEATURE)

    private val EMULATED_HARDWARE = setOf("ranchu", "goldfish")

    const val STRONGBOX_FEATURE = "android.hardware.strongbox_keystore"
    const val HARDWARE_KEYSTORE_FEATURE = "android.hardware.hardware_keystore"
    const val FINGERPRINT_FEATURE = "android.hardware.fingerprint"
}
