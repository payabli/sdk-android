package com.payabli.sdk.payin.telemetry

import com.payabli.sdk.core.telemetry.TelemetryBootstrap
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An app that depends on this artifact alone reports.
 *
 * This module's runtime classpath is what such an app gets, so the session's lookup has to resolve here. What a
 * resolved module does when the session starts it is `:core`'s `TelemetryBootstrapTest`.
 */
class PayInBringsTelemetryTest {
    @Test
    fun theTelemetryModuleIsOnThisArtifactsClasspath() {
        val found = Class.forName(TelemetryBootstrap.IMPLEMENTATION).getDeclaredConstructor().newInstance()

        assertTrue(found.javaClass.name, found is TelemetryBootstrap)
    }
}
