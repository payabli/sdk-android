package com.payabli.sdk.core

import androidx.annotation.RestrictTo

/**
 * What the SDK can do right now, and nothing else.
 *
 * Three values, so a host has one branch to write and one recovery to implement. The set is fixed by the
 * design rather than by this file; adding a fourth is not a local decision.
 *
 * **Claim-free**: no associated values, no token, no expiry, no assurance level. A state carrying a claim
 * would be a way to read one, which is what the App and SDK boundary exists to prevent.
 *
 * `@RestrictTo`, matching `PayabliSession.state`, until the set is frozen at GA.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed interface SdkState {
    /**
     * No session yet.
     *
     * A holder of a `PayabliSession` never observes this, because `state` is a property of a session and a
     * session exists only once `initialize` has succeeded. It is the starting value the machine transitions
     * out of, and it is meaningful to a caller only if the state is ever published somewhere a session is
     * not needed to reach it. Whether it should be is a question for the surface freeze, not for this file.
     */
    public data object Uninitialized : SdkState

    /** A session is live and requests can be made. */
    public data object Ready : SdkState

    /**
     * The session cannot be recovered from inside the SDK; call `PayabliSession.initialize` again.
     *
     * Terminal for this instance, with no transition out: re-initializing builds a new session rather than
     * reviving this one.
     */
    public data object ReinitializeRequired : SdkState
}
