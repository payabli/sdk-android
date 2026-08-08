package com.payabli.example.app.terminal

/**
 * Where the card-present session has got to.
 *
 * Named for what the terminal is doing. These are what the screen shows, and a reader watching a
 * payment fail needs to know which stage it failed at.
 */
enum class TerminalSessionState {
    /** Nothing has started. */
    Idle,

    /** Proving to the backend that this is a genuine device. */
    AttestingDevice,

    /** Fetching the configuration for this entry point. */
    FetchingConfig,

    /** Preparing the reader. The slowest stage, and the first one that touches hardware. */
    InitializingReader,

    /** Able to take a payment. */
    Ready,

    /** The session outlived its lifetime and needs starting again. */
    SessionExpired,

    /** Starting again after expiry. */
    Reinitializing,

    /** The backend knows this device but it has not been activated for card-present yet. */
    PendingActivation,

    /** Something failed. The last result line says what. */
    Error,
}
