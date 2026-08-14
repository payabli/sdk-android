package com.payabli.example.app

/**
 * What every instrumented test that installs an SDK session has to agree on.
 *
 * A session installs process-wide and `reset` is internal to `:core`, so the first test to install one
 * decides what the rest get: the SDK refuses a later `initialize` naming a different configuration, by
 * design. Two classes here reach that far and they share one process, so the entry point is one value
 * rather than a constant per class.
 *
 * Measured, because the failure does not name its cause: with two values, the class that ran second failed
 * on a session it never installed, and the class that caused it passed.
 */
internal object InstrumentedSession {
    /** Reaches no service. Every test that installs a session answers its own requests. */
    const val ENTRY_POINT = "instrumented-entry"
}
