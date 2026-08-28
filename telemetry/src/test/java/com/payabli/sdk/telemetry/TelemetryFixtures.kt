package com.payabli.sdk.telemetry

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetrySessionContext

/** The device these tests report from. Fixed, because the wire-shape assertion is a literal. */
internal fun aDevice() =
    TelemetryDeviceContext(
        idHash = "9f2c4b7e1a05d38c6e4b90f7c2a1d5e3",
        type = "Softpos",
        os = "Android",
        osVersion = "14",
        modelName = "Pixel 7a",
    )

/** The session these tests report under. [entryPoint] varies where a test needs two of them. */
internal fun aSession(
    entryPoint: String = "an-entry-point",
    sessionId: String = "0f8d2a1c-4b6e-4a2f-9c3d-5e7f8a9b0c1d",
    environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
) = TelemetrySessionContext(
    entryPoint = entryPoint,
    environment = environment,
    telemetryEnabled = true,
    sessionId = sessionId,
    device = aDevice(),
)
