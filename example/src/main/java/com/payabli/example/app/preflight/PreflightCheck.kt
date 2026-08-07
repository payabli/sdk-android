package com.payabli.example.app.preflight

/** How a single readiness check came out. */
enum class CheckStatus {
    /** Nothing to say. A passing check is not news and is not shown. */
    Pass,

    /** Works, but not in a way worth trusting. Does not block the verdict. */
    Warn,

    /** Card-present cannot work on this device until this is fixed. */
    Fail,

    /** The check could not be run here. Not the same as a failure, and must not read like one. */
    Unknown,
}

/**
 * One thing that has to be true before this device can take a contactless payment.
 *
 * @param detail what was actually observed, in enough words to act on. "NFC is switched off" is a
 *   next step; "NFC check failed" is not.
 */
data class PreflightCheck(
    val title: String,
    val detail: String,
    val status: CheckStatus,
)

/** The verdict across every check. */
enum class Readiness(
    val title: String,
) {
    Ready("Ready to take payments"),
    NotAvailable("Cannot take payments"),
}

/**
 * A hard failure blocks; a warning or an unrunnable check does not.
 *
 * Warnings do not block. A device with NFC switched off can take a payment as soon as someone turns
 * it on, and reporting that as "cannot take payments" would send a reader looking for a fault in the
 * app.
 */
fun readinessFrom(checks: List<PreflightCheck>): Readiness =
    if (checks.any { it.status == CheckStatus.Fail }) Readiness.NotAvailable else Readiness.Ready

/** The checks worth showing: the ones that are not simply fine. */
fun problemsIn(checks: List<PreflightCheck>): List<PreflightCheck> = checks.filter { it.status != CheckStatus.Pass }
