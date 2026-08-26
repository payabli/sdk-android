package com.payabli.sdk.taptopay.enrollment.platform

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.EnrollmentOutcome
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse

/**
 * The setup every live class needs: a session, an enrolled device, and the two run-wide constants.
 *
 * **Nothing clears the stored device record, and no live class may.** Recognition is local, so a run
 * starting without it registers another device for the same handset. Clearing the app's data is what
 * exercises the cold path.
 */
internal object LiveTapToPay {
    /** One tag for this tier, so a live run's output is one logcat filter. */
    const val LIVE_TAG: String = "PayabliLiveRun"

    private val EMULATED = setOf("ranchu", "goldfish")

    /**
     * Fails rather than skips. A live class is only ever invoked by name, so reaching it on an emulator
     * means the run was pointed at the wrong target, and a skip there reads as a run that went fine.
     */
    fun requireWiredHandset(reason: String) = assertFalse(reason, Build.HARDWARE in EMULATED)

    suspend fun session(context: Context): PayabliSession =
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

    suspend fun enrollment(context: Context): DeviceEnrollment {
        val trust = DeviceTrust.open(context)
        return DeviceEnrollment(
            entry = LiveRunSettings.entry,
            appId = context.packageName,
            client = DeviceServiceClient(session(context).transport),
            // Classic, to match the challenge the enrollment path builds. A standard attestor refuses one.
            attestor = AttestorFactory.classic(context, cloudProjectNumber()),
            deviceKey = trust.key,
            signer = DeviceAssertionSigner(trust.key),
            store = AttestedDeviceStore(trust.store),
            description = DeviceDescriptionFactory.create(context),
            dispatcher = Dispatchers.IO,
        )
    }

    /**
     * The device identifier for this handset, enrolling and activating it when there is not one yet.
     *
     * A device is recognised by the handset's own stable identity, so the first run registers and activates
     * and every later run is warm.
     */
    suspend fun activatedDeviceId(context: Context): String {
        val enrollment = enrollment(context)
        val outcome = enrollment.enroll()
        val record =
            AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)
                ?: error("the cold sequence recorded nothing to charge with")

        if (outcome is EnrollmentOutcome.Attested && outcome.activationRequired) {
            // Playing the merchant's part. The SDK cannot mint its own code, and the route needs a device
            // handle that exists only once it has registered.
            enrollment.activateDevice(mintActivationCode(record.deviceId))
        }
        return record.deviceId
    }

    fun mintActivationCode(deviceId: String): String =
        ActivationCodeMinter.mint(
            baseUrl = LiveRunSettings.baseUrl,
            accessToken = LiveRunSettings.accessToken(),
            entry = LiveRunSettings.entry,
            deviceId = deviceId,
        )

    fun cloudProjectNumber(): Long =
        InstrumentationRegistry.getArguments().getString("cloudProjectNumber")?.toLongOrNull()
            ?: error("payabli.cloudProjectNumber is required for the live tier; pass -Ppayabli.cloudProjectNumber=<n>")
}
