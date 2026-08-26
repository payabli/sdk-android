package com.payabli.example.app.demo.terminal

/**
 * Why the terminal stopped, in terms of what the reader in front of it can do.
 *
 * The SDK reports a fixed vocabulary and the screen shows one line per member, so a merchant is told
 * whether to wait, to change something, or to fetch a different device.
 */
enum class TerminalFailureReason(
    val message: String,
) {
    AttestationRequired("This device has to prove its identity again. Set up the terminal."),
    ConfigurationRejected("This paypoint or device is not set up for card-present payments."),
    ServiceUnavailable("The service could not be reached. Try again."),
    DeviceIneligible("This device cannot take contactless payments."),
    SdkInternalError("The SDK reported a problem it cannot repair."),
}
