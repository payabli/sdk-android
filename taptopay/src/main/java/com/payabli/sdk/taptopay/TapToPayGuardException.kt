package com.payabli.sdk.taptopay

/**
 * A guard in this SDK refused its own sequence. That is a defect in the SDK rather than a refusal a host can
 * correct, so it is told apart from a state check a collaborator raised. Its message is SDK text naming the guard.
 */
internal class TapToPayGuardException(
    message: String,
) : IllegalStateException(message)

/** [check], raising the SDK's own guard failure. */
internal inline fun checkGuard(
    value: Boolean,
    message: () -> String,
) {
    if (!value) throw TapToPayGuardException(message())
}

/** [error], raising the SDK's own guard failure. */
internal fun guardFailed(message: String): Nothing = throw TapToPayGuardException(message)
