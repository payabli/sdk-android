package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import android.os.SystemClock
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.ChargeKeyStore
import com.payabli.sdk.taptopay.PayabliTTP
import com.payabli.sdk.taptopay.TapToPayChargeRunner
import com.payabli.sdk.taptopay.attestation.AttestationProjectStore
import com.payabli.sdk.taptopay.attestation.MintProjectResolver
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.platform.DeviceDescriptionFactory
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import kotlinx.coroutines.Dispatchers

/** The whole of the card-present wiring. The SDK admits no dependency-injection framework. */
internal object TapToPayComponents {
    suspend fun build(
        session: PayabliSession,
        context: Context,
    ): PayabliTTP {
        val application = context.applicationContext
        // The session's own, which is what it publishes this for: a capability shipped as its own artifact
        // has no other way to learn which entry point it is working against, and taking it again as a
        // parameter would let a terminal be pointed somewhere the session was never configured for.
        val entryPoint = session.telemetry.entryPoint
        val environment = session.telemetry.environment
        val trust = DeviceTrust.open(application)
        val store = AttestedDeviceStore(trust.store)
        forgetLegacyChargeKeys(trust.store)
        val projects = AttestationProjectStore(trust.store)
        val mintProject = MintProjectResolver { projects.require(environment) }
        val deviceService = DeviceServiceClient(session.transport)
        val enrollment =
            DeviceEnrollment(
                entry = entryPoint,
                appId = application.packageName,
                client = deviceService,
                // Classic, to match the challenge the enrollment path builds. The project is Payabli's:
                // enrollment pins the number from this challenge through its mint, and a later mint with
                // no response in hand falls back to the environment's stored latest.
                attestor = AttestorFactory.classic(application) { mintProject.resolve() },
                deviceKey = trust.key,
                signer = DeviceAssertionSigner(trust.key),
                store = store,
                projects = projects,
                mintProject = mintProject,
                environment = environment,
                description = DeviceDescriptionFactory.create(application),
                dispatcher = Dispatchers.IO,
            )

        // One manager and one reader for both halves: a charge repairs the session the coordinator built.
        val manager = TapToPaySessionManager()
        val reader = CardReaders.fiserv(application)
        val coordinator =
            TapToPaySessionCoordinator(
                entry = entryPoint,
                enrollment = enrollment,
                client = deviceService,
                reader = reader,
                manager = manager,
            )
        return PayabliTTP.over(
            coordinator = coordinator,
            runner =
                TapToPayChargeRunner(
                    entry = entryPoint,
                    // The backend the payment is opened against: `client` below is built over
                    // `session.transport`, so a retained payment has to be scoped to the service that
                    // transport reaches.
                    environment = environment,
                    coordinator = coordinator,
                    manager = manager,
                    reader = reader,
                    client = TTPTransactionClient(session.transport),
                    store = store,
                    // Process-scoped: a screen rebuild reuses the key; a process death does not, matching
                    // the token, the session and the pending-close handle.
                    keys = ChargeKeyStore(elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos),
                ),
        )
    }

    /**
     * Drops encrypted entries an earlier build wrote for held keys. The store no longer reads them; this
     * only clears dead blobs so they do not linger beside the device binding.
     */
    private suspend fun forgetLegacyChargeKeys(storage: PayabliSecureStorage) {
        try {
            storage.remove(ChargeKeyStore.LEGACY_ENTRY)
        } catch (_: SecureStorageException) {
            // Best-effort cleanup of a blob nothing reads; a failed remove must not stop wiring.
        }
        try {
            storage.remove(ChargeKeyStore.LEGACY_PREVIOUS_ENTRY)
        } catch (_: SecureStorageException) {
            // Same as above.
        }
    }
}
