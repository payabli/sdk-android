package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.session.TapToPaySessionState.AttestingDevice
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
 * Three rules hold from every state and are stated here rather than repeated in nine rows: re-entering the
 * current state is legal and publishes nothing, starting over is always reachable, and failing is always
 * reachable. The sibling SDK declares the first two and reaches its failure state from three states its own
 * table forbids, by writing that one directly instead of going through the table. Declaring the edge is the
 * same behaviour with one writer instead of two.
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
            else -> to in reachableFrom(from)
        }

    /**
     * The states reachable from [from] by a move the rules above do not already allow.
     *
     * An exhaustive `when` rather than a map, so a tenth state fails to compile here. A map would answer a
     * state it has no row for with an empty set, which reads as a legitimate dead end.
     */
    private fun reachableFrom(from: TapToPaySessionState): Set<TapToPaySessionState> =
        when (from) {
            Idle -> setOf(AttestingDevice, FetchingConfig)
            AttestingDevice -> setOf(FetchingConfig, PendingActivation)
            FetchingConfig -> setOf(InitializingReader, PendingActivation)
            InitializingReader -> setOf(Ready)
            Ready -> setOf(SessionExpired)
            // Only into a re-initialization. Reaching config directly from here would skip the state that
            // says a repair is under way, and that state is what a host shows.
            SessionExpired -> setOf(Reinitializing)
            Reinitializing -> setOf(FetchingConfig)
            // The device owes a code. Confirming it puts the session back through attestation rather than
            // straight to config, because the service issues the credentials only to an active device.
            PendingActivation -> setOf(AttestingDevice)
            is Failed -> setOf(AttestingDevice, FetchingConfig)
        }
}
