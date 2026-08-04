package com.payabli.sdk.taptopay.attestation

import androidx.annotation.RestrictTo

/**
 * An attestation token, carried from the platform to a server and **not read on the way**.
 *
 * The token is a signed, encrypted document addressed to a verifier that holds the decryption key. This
 * SDK is not that verifier. Nothing here parses it, splits it, measures it against a shape or decides
 * anything from its contents, and a change that starts doing so is the defect this type exists to make
 * obvious: an integrity verdict a client evaluated is an integrity verdict an attacker can arrange.
 *
 * So the only operations are "carry it" and "hand it over". [value] is what goes on the wire, byte for
 * byte as the platform produced it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@JvmInline
public value class AttestationToken(
    /** The opaque token, verbatim. */
    public val value: String,
) {
    /**
     * Never the token.
     *
     * It is not a bearer credential for our API, so this is not the secret-logging rule; it is the replay
     * rule. A token is valid for one verification, and one that has been written to a log has been written
     * somewhere that outlives the request it was minted for.
     */
    override fun toString(): String = "AttestationToken(REDACTED)"
}
