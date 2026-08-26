package com.payabli.sdk.taptopay.adapters.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionException
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 300.seconds

/**
 * The real reader, brought up against a real paypoint. No card is presented.
 *
 * This is the step the session tier stands in for: everything up to and including arming the vendor's
 * reader with the credentials the paypoint returned.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=qa \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.adapters.platform.FiservCardReaderLiveTest
 * ```
 *
 * The first arming on a handset can take minutes; later ones are quick.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class FiservCardReaderLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() =
        LiveTapToPay.requireWiredHandset("bringing a reader up is for wired handsets: an emulator has none")

    @Test
    fun theReaderComesUpForTheRealPaypoint() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val coordinator =
                    TapToPaySessionCoordinator(
                        entry = LiveRunSettings.entry,
                        enrollment = LiveTapToPay.enrollment(context),
                        client = DeviceServiceClient(LiveTapToPay.session(context).transport),
                        reader = CardReaders.fiserv(context),
                        manager = TapToPaySessionManager(),
                    )

                val owed = runCatching { coordinator.initialize() }.exceptionOrNull()
                if (owed is TapToPaySessionException.PendingActivation) {
                    val record =
                        AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)
                            ?: error("the session asked for activation and recorded nothing to activate")
                    Log.i(LiveTapToPay.LIVE_TAG, "device owes a code; minting one out of band")
                    coordinator.activateDevice(LiveTapToPay.mintActivationCode(record.deviceId))
                    coordinator.initialize()
                } else if (owed != null) {
                    throw owed
                }

                assertEquals(TapToPaySessionState.Ready, coordinator.state.value)
                Log.i(LiveTapToPay.LIVE_TAG, "the reader is armed for ${LiveRunSettings.environment}")
            }
        }
}
