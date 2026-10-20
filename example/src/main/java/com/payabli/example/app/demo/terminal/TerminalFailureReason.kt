package com.payabli.example.app.demo.terminal

/**
 * Why the terminal stopped, in terms of what the reader in front of it can do: wait, change
 * something, or fetch a different device. [DeviceIneligible] has no line of its own, because
 * four error codes land it and a line fixed to the reason cannot say which.
 */
enum class TerminalFailureReason(
    val message: String,
) {
    DeviceSetupRequired("This device has to prove its identity again. Set up the terminal."),
    ConfigurationRejected("This paypoint or device is not set up for card-present payments."),
    ServiceUnavailable("The service could not be reached. Try again."),

    /**
     * This handset cannot take contactless payments as it stands, and nothing the app does
     * reaches it. Four error codes land here and a line fixed to the reason cannot say
     * which, so [message] is the line only when none was captured.
     */
    DeviceIneligible("The session stopped."),
    SdkInternalError("The SDK reported a problem it cannot repair."),
    DeviceKeyUnavailable("This phone's key facility failed. Set up again; if it keeps failing, the phone is at fault."),
    SessionEnded("This terminal's session was replaced. Open the terminal again."),
}
