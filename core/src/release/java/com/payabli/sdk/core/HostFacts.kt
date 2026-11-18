package com.payabli.sdk.core

import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.core.device.platform.DeviceProfileFactory
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext

/**
 * What a session reads from the host application, in the release build: always from a host, with the platform
 * identifier.
 */
internal class HostFacts private constructor(
    val device: TelemetryDeviceContext,
    val readDeviceId: () -> String?,
    val host: HostBindings,
) {
    companion object {
        /**
         * Reads the facts from [host].
         *
         * @param host the host application's bindings.
         * @return the facts read from [host].
         */
        fun of(host: HostBindings): HostFacts {
            val context = host.appContext.applicationContext
            return HostFacts(
                device = DeviceProfileFactory.of(host.appContext),
                readDeviceId = { sessionDeviceId(DeviceIdentifierFactory.of(context)) },
                host = host,
            )
        }
    }
}
