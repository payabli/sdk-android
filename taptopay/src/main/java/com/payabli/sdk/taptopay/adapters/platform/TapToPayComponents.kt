package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ChargeKeyStore
import com.payabli.sdk.taptopay.PayabliTTP
import com.payabli.sdk.taptopay.TapToPayChargeRunner
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
        val trust = DeviceTrust.open(application)
        val store = AttestedDeviceStore(trust.store)
        val deviceService = DeviceServiceClient(session.transport)
        val enrollment =
            DeviceEnrollment(
                entry = entryPoint,
                appId = application.packageName,
                client = deviceService,
                // Classic, to match the challenge the enrollment path builds, and with no cloud project
                // number: the platform makes it optional for a classic request because an app distributed
                // through Play already carries the linkage. An integrator cannot supply one, since the
                // service decodes every Android attestation against a single deployment-level project.
                attestor = AttestorFactory.classic(application, cloudProjectNumber = null),
                deviceKey = trust.key,
                signer = DeviceAssertionSigner(trust.key),
                store = store,
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
                    coordinator = coordinator,
                    manager = manager,
                    reader = reader,
                    client = TTPTransactionClient(session.transport),
                    store = store,
                    // Over the same backing store as the bindings, so a key outlives the terminal that
                    // reserved it and the process that held it.
                    keys = ChargeKeyStore(trust.store),
                ),
        )
    }
}
