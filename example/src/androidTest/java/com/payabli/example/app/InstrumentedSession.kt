package com.payabli.example.app

import com.payabli.example.app.demo.config.DemoEnvironment
import com.payabli.sdk.core.config.PayabliEnvironment

/**
 * What every instrumented test that installs an SDK session has to agree on.
 *
 * A session installs process-wide and `reset` is internal to `:core`, so the first test to install one
 * decides what the rest get: the SDK refuses a later `initialize` naming a different configuration, by
 * design. Two classes here reach that far and they share one process, so the entry point is one value
 * rather than a constant per class.
 *
 * With two values, whichever class installs its session second fails, on a session it never installed,
 * and the failure names neither the class that caused it nor the configuration it disagreed with.
 */
internal object InstrumentedSession {
    /** Names no paypoint. It stands in for the build setting a checkout does not carry. */
    const val ENTRY_POINT = "instrumented-entry"

    /**
     * Pinned as well as the entry point, because the SDK compares both.
     *
     * `PayabliSession.ConfigIdentity` holds the entry point, the environment, the telemetry flag and
     * whether a token provider was supplied. Agreeing on the entry point alone leaves a build configured
     * with `payabli.demo.environment` installing one environment here and another there, which fails
     * the same way and only on that build.
     */
    val ENVIRONMENT: DemoEnvironment = DemoEnvironment.SANDBOX

    /**
     * The same environment in the SDK's own vocabulary, for a test that configures the SDK directly rather
     * than through the app's own configuration.
     *
     * Derived from [ENVIRONMENT] through an exhaustive `when` rather than restated as a second constant, so
     * the two cannot drift and a renamed enum constant is a compile error. The app has a mapping of its own
     * and it is private to the file that builds the config.
     */
    val SDK_ENVIRONMENT: PayabliEnvironment
        get() =
            when (ENVIRONMENT) {
                DemoEnvironment.SANDBOX -> PayabliEnvironment.SANDBOX
                DemoEnvironment.PRODUCTION -> PayabliEnvironment.PRODUCTION
            }
}
