package com.payabli.sdk.taptopay.attestation

import androidx.annotation.RestrictTo

/**
 * Produces an attestation token over a server-issued challenge.
 *
 * Free of `android.*` imports, so a consumer can be unit-tested against a fake with no device. The Play
 * Integrity implementation lives in `platform`, and nothing from it appears here: no platform manager, no
 * `Task`, no error enum. That is what lets the layers above this be tested at all, since none of the
 * platform's attestation surface has an off-device implementation.
 *
 * **What the token means is a server's question, not this SDK's.** An implementation obtains it and hands
 * it back; it does not read it, and a caller that decides anything locally from a verdict has been given a
 * decision the attacker controls.
 *
 * An implementation is safe to share and safe to call concurrently.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface AppAttestor {
    /**
     * Attests over [challenge], returning the platform's opaque token.
     *
     * **The challenge is spent once the request is attempted, and not before.** A second call with the same
     * one then fails with [AttestationException.ChallengeReused] rather than producing a second token,
     * whether or not the first attempt succeeded: what makes a challenge single-use is that it was offered
     * to the platform, not that it worked.
     *
     * Two refusals happen ahead of that point and leave the challenge unspent, so a caller holding one can
     * present it again. A challenge built for the other request shape is rejected as an
     * `IllegalArgumentException`, and a request refused locally because the shared budget is spent throws
     * [AttestationException.Throttled] without reaching the platform. Neither consumed anything, and
     * treating them as spent would make a caller discard a value it can still use.
     *
     * Every failure is an [AttestationException], and its subtype says what to do. Cancellation propagates
     * as `CancellationException` in the usual way.
     */
    public suspend fun attest(challenge: AttestationChallenge): AttestationToken

    /**
     * Does whatever setup the underlying request shape needs, ahead of needing it.
     *
     * Idempotent, safe to call concurrently, and safe to skip: [attest] does the same work on its own if
     * this was never called. It exists because one of the two shapes prepares a provider through a network
     * round trip, and paying that inside the first attestation puts seconds into whatever the user was
     * waiting for. A failure here is the same [AttestationException] [attest] would have raised.
     */
    public suspend fun warmUp()
}
