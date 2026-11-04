package com.payabli.example.app.demo.terminal

/** Something the terminal reported while it was working. */
enum class TerminalEventCode {
    AttestationStarted,
    AttestationCompleted,
    ConfigReceived,
    ReaderInitializing,
    ReaderReady,
    ChargeInitiated,
    NfcStarted,
    NfcCompleted,
    NfcFailed,
    UpdateCompleted,
    UpdateFailed,
    SessionExpired,
    ReinitializeStarted,
    ReinitializeCompleted,
    DevicePendingActivation,
    ActivationStarted,
    ActivationCompleted,
    ActivationFailed,
    SetupFailed,
    ;

    /** The event's name in camelCase. */
    val eventName: String get() = name.replaceFirstChar { it.lowercase() }
}

/**
 * One entry in the stream.
 *
 * @param detail already formatted for display and free of anything sensitive. No card number, token,
 *   expiry or account number ever reaches this.
 */
data class TerminalEvent(
    val code: TerminalEventCode,
    val detail: String = "",
)

/** The step a failure event names in this app's words, or null for an event that is not a failed step. */
private val TerminalEventCode.failedStep: String?
    get() =
        when (this) {
            TerminalEventCode.SetupFailed -> "setup"
            TerminalEventCode.ActivationFailed -> "activation"
            TerminalEventCode.UpdateFailed -> "charge"
            else -> null
        }

/** The row's first line: a failed step and its code, or else the event's name. */
val TerminalEvent.label: String
    get() = code.failedStep?.let { "$it failed · $detail" } ?: code.eventName

/** The row's second line, empty for a failed step, whose detail is already on the first. */
val TerminalEvent.shownDetail: String
    get() = if (code.failedStep == null) detail else ""
