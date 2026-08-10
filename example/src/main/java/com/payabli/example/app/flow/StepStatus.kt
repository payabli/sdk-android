package com.payabli.example.app.flow

/**
 * Where one step of a flow has got to.
 *
 * A screen that asks the SDK for several things in order shows them as a numbered sequence, so a
 * reader can see what is required and what is next without running it.
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
     * Only the step being acted on, and one that failed. A failure keeps its controls because the
     * reason and the retry belong together; hiding them would leave a reader told that something
     * broke and given nothing to do about it.
     */
    val showsContent: Boolean get() = this == Current || this == Failed

    /** Whether this step is the one asking for something. */
    val isActionable: Boolean get() = showsContent

    /**
     * Whether the step after this one may proceed.
     *
     * A step that was skipped counts as finished; a step still working, blocked or failed does not.
     * Every sequence in this app reads this rather than deciding for itself, because a step that
     * consults the underlying state instead can offer itself alongside an earlier step that is still
     * asking for something.
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
 * Exactly one step is [StepStatus.Current], or none once the flow is finished. A second one would
 * put a reader in front of two things claiming to be next. A failure replaces it rather than joining
 * it: the failed step is the one to act on, and everything after it waits.
 */
fun List<FlowStep>.isWellFormed(): Boolean {
    val actionable = count { it.status.isActionable }
    return actionable <= 1
}
