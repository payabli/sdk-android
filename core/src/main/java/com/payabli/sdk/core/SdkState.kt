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
     * No session yet, which is what `PayabliSession.state` reads until `initialize` succeeds.
     *
     * Observable, and that is why the state is published on the companion rather than on a session: a value
     * only a session could reach could never be this one, and a sealed set with an unreachable member makes
     * every consumer write a branch that can never run.
     */
    public data object Uninitialized : SdkState

    /** A session is live and requests can be made. */
    public data object Ready : SdkState

    /**
     * The session cannot be recovered from inside the SDK; call `PayabliSession.initialize` again.
     *
     * Terminal for the session that reached it, which is never revived. The state leaves this value only
     * when a successor session becomes [Ready].
     */
    public data object ReinitializeRequired : SdkState
}
