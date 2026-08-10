package com.payabli.sdk.core

import androidx.annotation.RestrictTo

/**
 * What the SDK can do right now.
 *
 * Carries no token and no expiry. Reading the state tells a host what it may do, never what the
 * session holds.
 *
 * `@RestrictTo`, matching `PayabliSession.state`, until the set is frozen at GA.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed interface SdkState {
    /**
     * No session yet. `PayabliSession.state` reads this until `initialize` succeeds.
     *
     * This is why the state lives on the companion. On a session instance it could never be read: you
     * would need a session to observe the value that says there is not one, leaving every consumer
     * with a branch that never runs.
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
