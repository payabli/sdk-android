package com.payabli.sdk.taptopay.enrollment.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.EnrollmentOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 180.seconds

/**
 * A handset whose device key is deleted recovers on the next enrollment, against the real service.
 *
 * Invoked by name, as `DeviceActivationLiveTest` is, with the same properties and the token server running:
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=<name> \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.enrollment.platform.DeviceKeyRecoveryLiveTest
 * ```
 *
 * Leaves the handset activated under a new key, so the other live classes stay warm. Each run spends a
 * challenge and an activation code.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class DeviceKeyRecoveryLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("the live tier is for wired handsets")

    @Test
    fun aDeletedKeyIsReplacedUnderTheSameDeviceAndOwesANewCode() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val deviceId = LiveTapToPay.activatedDeviceId(context)
                val trust = DeviceTrust.open(context)
                val keyBefore = trust.key.publicKey().identity

                // Built before the delete: opening the device trust creates a key when there is none.
                val enrollment = LiveTapToPay.enrollment(context)
                trust.key.delete()
                val absent = runCatching { trust.key.publicKey() }.exceptionOrNull()
                assertTrue("the key must be gone when enroll runs, got $absent", absent is DeviceKeyException.KeyLost)

                val outcome = enrollment.enroll()

                val record =
                    AttestedDeviceStore(trust.store).read(LiveRunSettings.entry)
                        ?: error("the recovery recorded nothing")
                assertTrue(
                    "a replaced key must owe a new code, got $outcome",
                    (outcome as EnrollmentOutcome.Attested).activationRequired,
                )
                assertEquals("the service must keep the device", deviceId, record.deviceId)
                assertNotEquals(keyBefore, record.keyId)
                assertEquals(trust.key.publicKey().identity, record.keyId)

                enrollment.activateDevice(LiveTapToPay.mintActivationCode(record.deviceId))
                assertEquals(EnrollmentOutcome.AlreadyAttested, enrollment.enroll())
            }
        }
}
