package com.payabli.sdk.taptopay.enrollment.platform

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.adapters.platform.looksEmulated
import com.payabli.sdk.taptopay.attestation.AttestationProjectStore
import com.payabli.sdk.taptopay.attestation.MintProjectResolver
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.EnrollmentOutcome
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse

/**
 * The setup every live class needs: a session, an enrolled device, and the two run-wide constants.
 *
 * **A live class keeps the stored device record.** Recognition is local, so a run starting without it
 * registers another device for the same handset.
 *
 * One exception, and it is the reason the rule is stated rather than assumed: `DeviceActivationLiveTest`
 * drives the cold sequence, so it clears the record in `@After` and accepts a new device per run. A class
 * that is not exercising that path clears nothing.
 */
internal object LiveTapToPay {
    /** One tag for this tier, so a live run's output is one logcat filter. */
    const val LIVE_TAG: String = "PayabliLiveRun"

    /**
     * The payer every opening in this tier names.
     *
     * An opening whose first name, last name and customer number are all empty is refused with `400` and
     * `E7020`. The name reads as a test's on the paypoint, where these rows stay.
     */
    val PAYER: TapToPayCustomerData =
        TapToPayCustomerData(
            firstName = "Payabli",
            lastName = "LiveTier",
            customerNumber = "sdk-android-live-tier",
        )

    /**
     * Fails rather than skips. A live class is only ever invoked by name, so reaching it on an emulator
     * means the run was pointed at the wrong target, and a skip there reads as a run that went fine.
     *
     * The SDK's own detector, so every image it recognises is refused here too. A guard that reads fewer
     * build values than the SDK lets an image through to a route that charges a real paypoint.
     */
    fun requireWiredHandset(reason: String) = assertFalse(reason, looksEmulated())

    suspend fun session(context: Context): PayabliSession =
        PayabliSession
            .initialize(
                PayabliConfig(
                    entryPoint = LiveRunSettings.entry,
                    environment = LiveRunSettings.environment,
                    // The provider is the only way a token reaches the SDK now, and it mints a fresh one
                    // per call, which is what keeps a long live sequence off a single expiring token.
                    tokenProvider = { LiveRunSettings.accessToken() },
                ),
                HostBindings(context),
            ).getOrThrow()

    suspend fun enrollment(context: Context): DeviceEnrollment {
        val trust = DeviceTrust.open(context)
        val projects = AttestationProjectStore(trust.store)
        val environment = LiveRunSettings.environment
        val mintProject = MintProjectResolver { projects.require(environment) }
        return DeviceEnrollment(
            entry = LiveRunSettings.entry,
            appId = context.packageName,
            client = DeviceServiceClient(session(context).transport),
            // Classic, to match the challenge the enrollment path builds. A standard attestor refuses one.
            // The store is the shipping source; the Gradle number seeds it so a live run can point at a
            // project before the challenge answers, and a challenge that carries one overwrites it.
            attestor = AttestorFactory.classic(context) { mintProject.resolve() },
            deviceKey = trust.key,
            signer = DeviceAssertionSigner(trust.key),
            store = AttestedDeviceStore(trust.store),
            projects = projects,
            mintProject = mintProject,
            environment = environment,
            description = DeviceDescriptionFactory.create(context),
            dispatcher = Dispatchers.IO,
        ).also { projects.remember(environment, cloudProjectNumber()) }
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

    /**
     * The project this run attests against.
     *
     * Configured rather than committed: the live tier has to be pointable at a project without rebuilding.
     * The shipping path reads the same number from the challenge response; this seed covers the bench's
     * ability to name a project explicitly before that response arrives.
     */
    fun cloudProjectNumber(): Long =
        InstrumentationRegistry.getArguments().getString("cloudProjectNumber")?.toLongOrNull()
            ?: error(
                "payabli.cloudProjectNumber is required for the live tier; pass -Ppayabli.cloudProjectNumber=<n>",
            )
}
