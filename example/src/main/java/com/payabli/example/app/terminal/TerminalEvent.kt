package com.payabli.example.app.terminal

/**
 * Something the terminal reported while it was working.
 *
 * The wire names are kept as written. Anyone reading this stream is comparing it against a backend
 * log or a support ticket, where a friendlier label would have to be translated back first.
 */
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
    ;

    /** camelCase, matching how these appear on the wire. */
    val wireName: String get() = name.replaceFirstChar { it.lowercase() }
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
