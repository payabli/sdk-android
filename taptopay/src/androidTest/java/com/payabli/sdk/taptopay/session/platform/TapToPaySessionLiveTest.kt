package com.payabli.sdk.taptopay.session.platform

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.platform.ActivationCodeMinter
import com.payabli.sdk.taptopay.enrollment.platform.DeviceDescriptionFactory
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * ready. Every step of that is the shipped path; the only stand-in is the reader itself, because the vendor
 * adapter is a later phase and there is nothing yet to arm.
 *
 * **So this is the live proof of two things that had none.** The session machine has had no production
 * construction site since it was written, so nothing had ever driven it against the service; and `/config`
 * had never been shown to return a real paypoint's reader credentials to an activated device. What it
 * cannot show is the reader coming up, which is the one step the stand-in replaces.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=qa \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.session.platform.TapToPaySessionLiveTest
 * ```
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
        // Fails rather than skips. This class is only ever invoked by name, so reaching it on an emulator
        // means the run was pointed at the wrong target, and a skip there reads as a run that went fine.
        // Without this the run dies further in, at the platform verdict, and reports an integrity code that
        // reads like a service problem: measured on a non-Play-Store image as RemediationRequired(-14).
        // A denylist, so an emulator nobody has named fails here too.
        assertFalse(
            "bringing a terminal up is for wired handsets: an emulator has no reader and no Play Store",
            Build.HARDWARE in EMULATED,
        )
    }

    @After
    fun forgetTheDevice() =
        runTest(timeout = TEST_TIMEOUT) {
            // The service's row stays: it is what the next run is recognised by, and what keeps a later run
            // from spending another activation attempt.
            AttestedDeviceStore(DeviceTrust.open(context).store).clear(LiveRunSettings.entry)
        }

    @Test
    fun aSessionReachesReadyWithTheRealPaypointsReaderCredentials() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val reader = StubReader()
                val enrollment = enrollment()
                val coordinator =
                    TapToPaySessionCoordinator(
                        entry = LiveRunSettings.entry,
                        enrollment = enrollment,
                        client = DeviceServiceClient(session().transport),
                        reader = reader,
                        manager = TapToPaySessionManager(),
                    )

                val owed = runCatching { coordinator.initialize() }.exceptionOrNull()
                if (owed is TapToPaySessionException.PendingActivation) {
                    // Playing the merchant's part. The SDK cannot mint its own code.
                    val record =
                        AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)
                            ?: error("the session asked for activation and recorded nothing to activate")
                    Log.i(LIVE_TAG, "device owes a code; minting one out of band")
                    coordinator.confirmActivation(
                        ActivationCodeMinter.mint(
                            baseUrl = LiveRunSettings.baseUrl,
                            accessToken = LiveRunSettings.accessToken(),
                            entry = LiveRunSettings.entry,
                            deviceId = record.deviceId,
                        ),
                    )
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
                    LIVE_TAG,
                    "session ready for ${LiveRunSettings.entry} on ${credentials.platform}, " +
                        "credentials complete",
                )
            }
        }

    private suspend fun session(): PayabliSession =
        PayabliSession
            .initialize(
                PayabliConfig(
                    accessToken = LiveRunSettings.accessToken(),
                    entryPoint = LiveRunSettings.entry,
                    environment = LiveRunSettings.environment,
                    tokenProvider = { LiveRunSettings.accessToken() },
                ),
                HostBindings(context),
            ).getOrThrow()

    private suspend fun enrollment(): DeviceEnrollment {
        val trust = DeviceTrust.open(context)
        return DeviceEnrollment(
            entry = LiveRunSettings.entry,
            appId = context.packageName,
            client = DeviceServiceClient(session().transport),
            // Classic, to match the challenge the enrollment path builds. A standard attestor refuses one.
            attestor = AttestorFactory.classic(context, cloudProjectNumber()),
            deviceKey = trust.key,
            signer = DeviceAssertionSigner(trust.key),
            store = AttestedDeviceStore(trust.store),
            description = DeviceDescriptionFactory.create(context),
            dispatcher = Dispatchers.IO,
        )
    }

    private fun cloudProjectNumber(): Long =
        InstrumentationRegistry.getArguments().getString("cloudProjectNumber")?.toLongOrNull()
            ?: error("payabli.cloudProjectNumber is required for the live tier; pass -Ppayabli.cloudProjectNumber=<n>")

    private companion object {
        val EMULATED = setOf("ranchu", "goldfish")

        /** One tag for this tier, so a live run's output is one logcat filter. */
        const val LIVE_TAG = "PayabliLiveRun"
    }
}
