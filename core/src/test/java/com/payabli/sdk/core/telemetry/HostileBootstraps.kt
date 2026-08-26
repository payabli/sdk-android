package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession

/**
 * Reporting modules that misbehave, named so the locator can be pointed at them.
 *
 * The locator finds its module by name, so a broken one has to be a real class with a no-argument
 * constructor rather than a lambda: pointing `implementation` at one of these reaches the same code by the
 * same route an integrator's stripped or mismatched artifact would.
 *
 * Public constructors, because the locator calls `getDeclaredConstructor().newInstance()`.
 */
class BootstrapThatThrowsOnStart : TelemetryBootstrap {
    override fun start(
        session: PayabliSession,
        host: HostBindings?,
    ): Unit = throw IllegalStateException("the queue could not be built")

    override fun stop() = Unit
}

/** A module whose `start` fails on a symbol that only resolves once the method runs. */
class BootstrapThatFailsToLink : TelemetryBootstrap {
    override fun start(
        session: PayabliSession,
        host: HostBindings?,
    ): Unit = throw NoSuchMethodError("com.payabli.sdk.core.telemetry.TelemetryRecorders.record")

    override fun stop() = Unit
}

/** A module that fails on the way in and again on the way out, which is the unwind path's own hazard. */
class BootstrapThatThrowsOnStartAndStop : TelemetryBootstrap {
    override fun start(
        session: PayabliSession,
        host: HostBindings?,
    ): Unit = throw IllegalStateException("the queue could not be built")

    override fun stop(): Unit = throw IllegalStateException("and it cannot be unwound either")
}

/** Counts what it was asked to do, for the assertions about being stopped and forgotten. */
class CountingBootstrap : TelemetryBootstrap {
    override fun start(
        session: PayabliSession,
        host: HostBindings?,
    ) {
        starts++
    }

    override fun stop() {
        stops++
    }

    companion object {
        var starts: Int = 0
        var stops: Int = 0

        fun reset() {
            starts = 0
            stops = 0
        }
    }
}
