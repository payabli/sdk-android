package com.payabli.example.app.preflight

/** How a single readiness check came out. */
enum class CheckStatus {
    /** Nothing to say. A passing check is not news and is not shown. */
    Pass,

    /**
     * Works, and not right now. Keeps the verdict off [Readiness.Ready] without making it
     * [Readiness.NotAvailable], because the device can do this once someone acts.
     */
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
    ActionNeeded("Ready once these are fixed"),
    NotAvailable("Cannot take payments"),
}

/**
 * Three verdicts, because two could not say what a device with NFC switched off is.
 *
 * A hard failure is [NotAvailable]: nothing anyone does to this device makes it take a payment. A
 * warning or an unrunnable check is [ActionNeeded]: the device can do this, and not right now. Only
 * a clean sweep is [Ready].
 *
 * With two verdicts a warning rolled into [Ready], so a phone with the radio switched off announced
 * "Ready to take payments" above a card listing the reason it could not. Folding it the other way is
 * no better, since "cannot take payments" sends a reader looking for a fault in the app.
 */
fun readinessFrom(checks: List<PreflightCheck>): Readiness =
    when {
        checks.any { it.status == CheckStatus.Fail } -> Readiness.NotAvailable
        checks.any { it.status != CheckStatus.Pass } -> Readiness.ActionNeeded
        else -> Readiness.Ready
    }

/** The checks worth showing: the ones that are not simply fine. */
fun problemsIn(checks: List<PreflightCheck>): List<PreflightCheck> = checks.filter { it.status != CheckStatus.Pass }
