package com.payabli.sdk.taptopay.attestation

/**
 * A platform attestation token, carried to the verifier byte for byte and never parsed: a verdict the
 * client evaluated is one an attacker can arrange. [value] is what goes on the wire.
 */
@JvmInline
internal value class AttestationToken(
    /** The opaque token, verbatim. */
    internal val value: String,
) {
    /**
     * Never the token: a token is valid for one verification, and a logged copy outlives that request.
     */
    override fun toString(): String = "AttestationToken(REDACTED)"
}
