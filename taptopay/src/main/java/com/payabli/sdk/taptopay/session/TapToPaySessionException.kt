package com.payabli.sdk.taptopay.session

/**
 * A card-present session could not be built or repaired.
 *
 * Distinct from the wire failures in the device package, which say what one call answered. These say what
 * happened to the session, and each one has a different thing a caller does next.
 */
internal sealed class TapToPaySessionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The device is registered but not active, so the merchant still owes it a code out of band.
     *
     * Not a defect and not retryable. A host collects the code and confirms it, and the session can then be
     * built.
     */
    class PendingActivation(
        cause: Throwable? = null,
    ) : TapToPaySessionException("the device is registered but has not been activated", cause)

    /**
     * The device's proof of identity is gone, so nothing short of attesting again will do.
     *
     * Raised where the stored record is absent, and where the service refuses the one it was given. Both
     * mean the same thing to a caller, and neither is repaired by re-initializing, which does not attest.
     */
    class AttestationRequired(
        cause: Throwable? = null,
    ) : TapToPaySessionException("the device must be attested again", cause)

    /**
     * A repair was asked for from a state that cannot be repaired.
     *
     * Building a session from the top is always available; this says only that the cheaper path is not.
     */
    class NotRecoverable(
        val state: TapToPaySessionState,
    ) : TapToPaySessionException("a session cannot be repaired from ${state.diagnosticName}")

    /**
     * The caller that owned this work withdrew, so it did not finish.
     *
     * What another caller waiting on the same work is given. Not the owner's cancellation, which would make
     * the waiter's own scope look like it is unwinding while nothing has cancelled it. Nothing is left
     * half-applied, and asking again is safe.
     */
    class SetupAbandoned : TapToPaySessionException("the caller that owned this session setup withdrew")
}
