package com.payabli.sdk.core

import android.content.Context
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.core.device.platform.DeviceProfileFactory
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext

/**
 * What a session reads from the host application, in the debug build, where the SDK's own tests can run with no
 * host or replace the identifier. The release build's copy always has a host and always reads the platform
 * identifier.
 */
internal class HostFacts private constructor(
    val device: TelemetryDeviceContext,
    val readDeviceId: () -> String?,
    val host: HostBindings?,
) {
    companion object {
        /** No host: no device to read and no identifier, which is the SDK's own tests on a JVM. */
        val NONE: HostFacts = HostFacts(TelemetryDeviceContext.NONE, { null }, null)

        /**
         * Reads the facts from [host].
         *
         * @param host the host application's bindings.
         * @param identifierOf reads the platform identifier, so a test can give a real device none.
         * @return the facts read from [host].
         */
        fun of(
            host: HostBindings,
            identifierOf: (Context) -> String = DeviceIdentifierFactory::of,
        ): HostFacts {
            val context = host.appContext.applicationContext
            return HostFacts(
                device = DeviceProfileFactory.of(host.appContext),
                readDeviceId = { identifierOf(context).ifBlank { null } },
                host = host,
            )
        }
    }
}
