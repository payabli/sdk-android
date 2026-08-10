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
     * The step being acted on, one that failed, and one that is working. A failure keeps its
     * controls because the reason and the retry belong together; hiding them would leave a reader
     * told that something broke and given nothing to do about it. A step that is working keeps them
     * because taking them off screen removes them from the composition, and anything they were
     * holding goes with it: the payment form keeps what was typed in `remember`, so hiding it during
     * a submission empties the form that a failure then asks the payer to fill in again.
     *
     * The controls of a working step are disabled by the state they are given, not by this.
     */
    val showsContent: Boolean get() = this == Current || this == Failed || this == InProgress

    /**
     * Whether this step is the one asking for something.
     *
     * Narrower than [showsContent]: a step that is working is on screen and is not asking.
     */
    val isActionable: Boolean get() = this == Current || this == Failed

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
