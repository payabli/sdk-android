package com.payabli.example.app.flow

/**
 * Where one step of a flow has got to.
 */
enum class StepStatus {
    /** Finished, and nothing more to do here. */
    Done,

    /** The next thing to do. */
    Current,

    /** Underway inside the SDK. The app is waiting, not the reader. */
    InProgress,

    /** Cannot run until an earlier step finishes. */
    Blocked,

    /** Does not apply to this device or this session. */
    NotNeeded,

    /** Attempted, and did not work. */
    Failed,
    ;

    /**
     * Whether this step shows its controls.
     *
     * A working step keeps them because hiding them disposes the composition, and the payment form's
     * typed values go with it.
     */
    val showsContent: Boolean get() = this == Current || this == Failed || this == InProgress

    /** Whether this step is the one asking for something. Narrower than [showsContent]. */
    val isActionable: Boolean get() = this == Current || this == Failed

    /**
     * Whether the step after this one may proceed. A skipped step counts as finished.
     *
     * A step that reads the underlying state instead can offer itself alongside an earlier step
     * that is still asking for something.
     */
    val isFinished: Boolean get() = this == Done || this == NotNeeded
}

/**
 * One step, as a screen describes it.
 *
 * @param title what the reader is doing.
 * @param detail what the SDK does at this point, in one line.
 */
data class FlowStep(
    val title: String,
    val detail: String,
    val status: StepStatus,
)

/**
 * The rule every sequence in this app follows.
 *
 * Exactly one step is [StepStatus.Current], or none once the flow is finished. A failure replaces
 * it: the failed step is the one to act on, and everything after it waits.
 */
fun List<FlowStep>.isWellFormed(): Boolean {
    val actionable = count { it.status.isActionable }
    return actionable <= 1
}
