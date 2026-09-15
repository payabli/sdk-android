package com.payabli.sdk.taptopay.session.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionException
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 180.seconds

/**
 * The session, against the real service, as far as a build can go without a reader.
 *
 * Attest, spend an activation code if one is owed, fetch the paypoint's reader credentials, and reach
 * ready. Every step of that is the shipped path; the reader is stood in for, which is what keeps this green
 * wherever the vendor's own device policy is not satisfied. `FiservCardReaderLiveTest` is the same walk with
 * the real reader on the end of it.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=<name> \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.session.platform.TapToPaySessionLiveTest
 * ```
 *
 * An environment beyond `sandbox` and `production` is not in this checkout. Add it to the build first,
 * with `payabli.sdk.extraEnvironments=<name>=https://<host>.payabli.com` in
 * `~/.gradle/gradle.properties`, or `payabli.ttp.environment` names one the SDK does not carry and the
 * run stops at configuration.
 *
 * **The credentials are live vendor secrets.** Nothing here prints one: what is asserted is that the fields
 * arrived and are not blank, which is the whole of what a reader would need to be handed.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class TapToPaySessionLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Stands in for the reader. It accepts what it is given and comes up, and does nothing else. */
    private class StubReader : TapToPayProvider {
        var credentials: ReaderCredentials? = null
            private set

        override suspend fun checkEligibility() = Unit

        override suspend fun configure(credentials: ReaderCredentials) {
            this.credentials = credentials
        }

        override suspend fun prepareReader() = Unit

        override suspend fun startReading(request: CardReadRequest): CardReadResult =
            throw UnsupportedOperationException("no reader is armed in this tier")
    }

    @Before
    fun requireHardware() {
        // Without this the run dies further in, at the platform verdict, and reports an integrity code that
        // reads like a service problem: measured on a non-Play-Store image as RemediationRequired(-14).
        LiveTapToPay.requireWiredHandset(
            "bringing a terminal up is for wired handsets: an emulator has no reader and no Play Store",
        )
    }

    @Test
    fun aSessionReachesReadyWithTheRealPaypointsReaderCredentials() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val reader = StubReader()
                val coordinator =
                    TapToPaySessionCoordinator(
                        entry = LiveRunSettings.entry,
                        enrollment = LiveTapToPay.enrollment(context),
                        client = DeviceServiceClient(LiveTapToPay.session(context).transport),
                        reader = reader,
                        manager = TapToPaySessionManager(),
                    )

                val owed = runCatching { coordinator.initialize() }.exceptionOrNull()
                if (owed is TapToPaySessionException.PendingActivation) {
                    // Playing the merchant's part. The SDK cannot mint its own code.
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

                // Never the values: two of these are the reader vendor's live API secrets.
                val credentials = reader.credentials ?: error("the session reached ready with no credentials")
                listOf(
                    "merchantId" to credentials.merchantId,
                    "terminalId" to credentials.terminalId,
                    "ppId" to credentials.ppId,
                    "apiKey" to credentials.apiKey,
                    "secretKey" to credentials.secretKey,
                ).forEach { (name, value) -> assertTrue("$name came back blank", value.isNotBlank()) }
                Log.i(
                    LiveTapToPay.LIVE_TAG,
                    "session ready for ${LiveRunSettings.entry} on ${credentials.platform}, " +
                        "credentials complete",
                )
            }
        }
}
