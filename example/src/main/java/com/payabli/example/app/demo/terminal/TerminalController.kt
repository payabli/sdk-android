package com.payabli.example.app.demo.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The card-present half of the seam.
 *
 * The Tap to pay screen talks to this and never to an SDK type. `TapToPayTerminal` is what the app
 * installs; [DemoTerminalController] walks the same sequence with no SDK behind it, for tests and for a
 * build with no paypoint to reach.
 *
 * Every action returns a [Result]. Each one has a visible failure path on screen, and a screen that
 * has to catch is a screen that will one day forget to.
 */
interface TerminalController {
    val sessionState: StateFlow<TerminalSessionState>

    val events: Flow<TerminalEvent>

    /** True when a payment can be taken right now. */
    val isReady: StateFlow<Boolean>

    /**
     * Why the session failed, or null while it has not.
     *
     * Its own flow, written wherever [sessionState] is, so the two cannot disagree about a failure.
     */
    val failureReason: StateFlow<TerminalFailureReason?>

    /**
     * The reason as the SDK holds it now, read rather than collected.
     *
     * [failureReason] is republished by a collector, which has not necessarily run by the time a call
     * that failed returns. A caller wording the outcome of that call needs the reason the SDK already
     * has.
     */
    fun currentFailureReason(): TerminalFailureReason?

    /** Attest, fetch configuration, prepare the reader. */
    suspend fun initialize(): Result<Unit>

    /** Start again after expiry. Succeeds without doing anything if the session is still good. */
    suspend fun reinitializeIfNeeded(): Result<Unit>

    /** Take a payment. [amount] is in major units. */
    suspend fun charge(amount: java.math.BigDecimal): Result<ChargeReceipt>

    /** Activate this device with a code issued by Payabli. */
    suspend fun activateDevice(activationCode: String): Result<Unit>
}

/** What a successful charge produced. Carries no card data of any kind. */
data class ChargeReceipt(
    val paymentTransactionId: String,
) {
    /** Presence, matching `TapToPayResult`: a data class prints every property it holds. */
    override fun toString(): String = "ChargeReceipt(hasPaymentTransactionId=${paymentTransactionId.isNotEmpty()})"
}
