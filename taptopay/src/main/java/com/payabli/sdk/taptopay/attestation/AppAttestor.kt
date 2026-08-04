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
     * **That refusal is a bounded caller-error guard, not replay protection.** It remembers a limited
     * number of recent values per attestor instance, so a value can be accepted again once enough others
     * have displaced it, and a second instance shares nothing with the first. Real single use is the
     * issuer's to enforce, since only it knows what it handed out and what it has retired. Do not build on
     * this as though it were authoritative.
     *
     * **Obtain a fresh challenge after any failure.** Some failures land before the request is attempted
     * and leave the value unspent: a challenge built for the other request shape, a local refusal for a
     * spent budget, and a standard preparation that fails before any request has carried the value.
     * [AttestationException.Retryable] covers both sides of that line, because the same platform code can
     * arrive from a preparation that never carried the challenge and from a request that did, so the
     * exception does not say which happened. Leaving the earlier cases unspent keeps a retry from being
     * refused as reuse; it does not make the value safe to reuse.
     *
     * **A failure originating at the platform is an [AttestationException]**, and its subtype says what to
     * do. Three things sit outside that. A challenge built for the other request shape is an
     * `IllegalArgumentException`, being a programming error rather than an attestation outcome.
     * Cancellation propagates as `CancellationException` in the usual way. A JVM `Error` propagates
     * unchanged, because an `OutOfMemoryError`, or a `LinkageError` from a platform library that is not on
     * the device, is not an attestation outcome and reporting it as one would hide it behind a retry.
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
