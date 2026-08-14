package com.payabli.sdk.taptopay.session

/**
 * Where a card-present session has got to.
 *
 * The same nine states the sibling SDK publishes, so an integrator moving between the platforms meets one
 * model. [Failed] carries a payload, which is why this is a sealed interface.
 *
 * **A failure names its reason.** Without one a consumer cannot tell an identity that was discarded from a
 * paypoint that was misconfigured, and has to assume the most expensive repair.
 *
 * [Failed], not `Error`: `kotlin.Error` is default-imported and is a `Throwable`, so a member of that name
 * needs qualifying anywhere a session and a throwable are handled together.
 */
internal sealed interface TapToPaySessionState {
    /** Nothing has been attempted, or the last attempt was withdrawn. Reachable from every state. */
    data object Idle : TapToPaySessionState

    /** Proving the device and the app to the service. Skipped by a warm start and by a re-initialization. */
    data object AttestingDevice : TapToPaySessionState

    /** Fetching the reader credentials, which is also where a warm start learns activation is still owed. */
    data object FetchingConfig : TapToPaySessionState

    data object InitializingReader : TapToPaySessionState

    /** The reader can take a payment. */
    data object Ready : TapToPaySessionState

    /**
     * The reader session died and the credentials behind it are spent.
     *
     * Repairable without attesting again, which is what separates it from [Failed].
     */
    data object SessionExpired : TapToPaySessionState

    data object Reinitializing : TapToPaySessionState

    /**
     * The service holds this device as registered but not yet active.
     *
     * The device owes a code the merchant issues out of band. Nothing the SDK can do advances this; a host
     * collects the code and confirms it.
     */
    data object PendingActivation : TapToPaySessionState

    /** The session cannot be used, and [reason] says what a host can do about it. */
    data class Failed(
        val reason: TapToPayFailureReason,
    ) : TapToPaySessionState
}

/**
 * The name for a log record. An exhaustive `when`, so adding a state fails to compile here and no name is
 * left for R8 to rewrite.
 *
 * [TapToPaySessionState.Failed]'s reason is recorded beside this as its own field, so a reader can group by
 * state without splitting one failure into four.
 */
internal val TapToPaySessionState.diagnosticName: String
    get() =
        when (this) {
            TapToPaySessionState.Idle -> "idle"
            TapToPaySessionState.AttestingDevice -> "attesting_device"
            TapToPaySessionState.FetchingConfig -> "fetching_config"
            TapToPaySessionState.InitializingReader -> "initializing_reader"
            TapToPaySessionState.Ready -> "ready"
            TapToPaySessionState.SessionExpired -> "session_expired"
            TapToPaySessionState.Reinitializing -> "reinitializing"
            TapToPaySessionState.PendingActivation -> "pending_activation"
            is TapToPaySessionState.Failed -> "failed"
        }
