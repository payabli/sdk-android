package com.payabli.example.app

import com.payabli.example.app.sdk.DemoEnvironment

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
     * `PayabliSession.ConfigIdentity` holds the entry point, the environment and the telemetry flag. A
     * provider is required, so its presence distinguishes nothing and is not compared. Agreeing on the
     * entry point alone leaves a build configured
     * with `payabli.demo.environment` installing one environment here and another there, which fails
     * the same way and only on that build.
     */
    val ENVIRONMENT: DemoEnvironment = DemoEnvironment.SANDBOX
}
