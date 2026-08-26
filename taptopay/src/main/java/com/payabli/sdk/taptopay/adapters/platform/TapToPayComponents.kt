package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
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
        entryPoint: String,
        cloudProjectNumber: Long?,
    ): PayabliTTP {
        val application = context.applicationContext
        val trust = DeviceTrust.open(application)
        val store = AttestedDeviceStore(trust.store)
        val deviceService = DeviceServiceClient(session.transport)
        val enrollment =
            DeviceEnrollment(
                entry = entryPoint,
                appId = application.packageName,
                client = deviceService,
                // Classic, to match the challenge the enrollment path builds.
                attestor = AttestorFactory.classic(application, cloudProjectNumber),
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
        return PayabliTTP(
            coordinator = coordinator,
            runner =
                TapToPayChargeRunner(
                    entry = entryPoint,
                    coordinator = coordinator,
                    manager = manager,
                    reader = reader,
                    client = TTPTransactionClient(session.transport),
                    store = store,
                ),
        )
    }
}
