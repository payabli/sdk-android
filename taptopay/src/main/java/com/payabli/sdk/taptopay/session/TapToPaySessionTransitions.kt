package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.session.TapToPaySessionState.AttestingDevice
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Charging
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Failed
import com.payabli.sdk.taptopay.session.TapToPaySessionState.FetchingConfig
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Idle
import com.payabli.sdk.taptopay.session.TapToPaySessionState.InitializingReader
import com.payabli.sdk.taptopay.session.TapToPaySessionState.PendingActivation
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Ready
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Reinitializing
import com.payabli.sdk.taptopay.session.TapToPaySessionState.SessionExpired

/**
 * Which moves between session states are legal.
 *
 * Separate from the machine that applies it so the table can be read, and tested, without a session.
 *
 * Three rules hold from every state and are stated once here: re-entering the current state is legal and
 * publishes nothing, starting over is always reachable, and failing is always reachable. Declaring the
 * failure edge keeps one writer for the state.
 */
internal object TapToPaySessionTransitions {
    fun permits(
        from: TapToPaySessionState,
        to: TapToPaySessionState,
    ): Boolean =
        when {
            from == to -> true
            to is Idle -> true
            to is Failed -> true
            else -> reaches(from, to)
        }

    /**
     * Whether [to] is reachable from [from] by a move the rules above do not already allow.
     *
     * An exhaustive `when`, so a new state fails to compile here. A map answers a state it has no row for
     * with an empty set, which reads as a legitimate dead end. A state that carries a value is matched by type.
     */
    private fun reaches(
        from: TapToPaySessionState,
        to: TapToPaySessionState,
    ): Boolean =
        when (from) {
            Idle -> to == AttestingDevice || to == FetchingConfig
            AttestingDevice -> to == FetchingConfig || to is PendingActivation
            FetchingConfig -> to == InitializingReader || to is PendingActivation
            InitializingReader -> to == Ready
            // A charge starts by opening the payment.
            Ready -> to == Charging(TapToPayChargeActivity.OPENING) || to == SessionExpired
            // Forward through the activities, then back to ready when the charge ends, whatever it ended in,
            // or expired when the read found the reader session spent.
            is Charging ->
                (to is Charging && advances(from.activity, to.activity)) || to == Ready || to == SessionExpired
            // Only into a re-initialization. Reaching config directly from here would skip the state that
            // says a repair is under way, and that state is what a host shows.
            SessionExpired -> to == Reinitializing
            Reinitializing -> to == FetchingConfig
            // The device owes a code. Confirming it puts the session back through attestation, since the
            // service issues the credentials only to an active device.
            is PendingActivation -> to == AttestingDevice
            is Failed -> to == AttestingDevice || to == FetchingConfig
        }

    /** A charge opens, waits for a card, then closes. A failed opening ends the charge without the other two. */
    private fun advances(
        from: TapToPayChargeActivity,
        to: TapToPayChargeActivity,
    ): Boolean =
        when (from) {
            TapToPayChargeActivity.OPENING -> to == TapToPayChargeActivity.WAITING_FOR_CARD
            TapToPayChargeActivity.WAITING_FOR_CARD -> to == TapToPayChargeActivity.CLOSING
            TapToPayChargeActivity.CLOSING -> false
        }
}
