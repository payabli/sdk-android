package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationToken
import java.security.MessageDigest
import java.util.Base64

/**
 * The two encodings between a challenge from the device service and an attestation it will accept.
 *
 * Both are pure and neither touches an Android type, so they are unit-testable and stay out of `platform/`.
 * `java.util.Base64` needs API 26 and this module's floor is 30.
 *
 * This is the highest-risk pair of functions in the package and the cheapest to pin, because an error in
 * either surfaces far from its cause: integrity verification fails, which reads as a device or configuration
 * problem and is actually an encoding one.
 */
internal object DeviceAttestationBinding {
    /**
     * The challenge to attest against, derived from the one the device service issued.
     *
     * **The value the platform signs is not the string that arrived.** It is the digest of the decoded
     * challenge, and all three steps are load-bearing: standard alphabet inbound, SHA-256, URL-safe base64
     * **without padding** outbound. A mismatch in any of them fails verification.
     *
     * Classic, never standard. Only this nonce shape is verified, whatever a later one may add, and a standard
     * request would bind its freshness to a `requestHash` that is not what gets checked. The returned challenge
     * therefore carries [com.payabli.sdk.taptopay.attestation.VerdictClass.CLASSIC], and the classic attestor
     * is the only one that will accept it.
     *
     * The result is 43 characters decoding to 32 bytes, so [AttestationChallenge.classic]'s shape rules are
     * satisfied by construction rather than by luck.
     *
     * A [challenge] that is not standard base64 is [DeviceServiceException.Undecodable]: what came back cannot
     * be used, which is a response defect and not a caller error. Only the
     * exception the decoder documents is caught, so an `OutOfMemoryError` raised mid-decode is not re-reported
     * as a malformed challenge — the same boundary [AttestationChallenge.classic] draws.
     */
    fun nonceChallenge(challenge: String): AttestationChallenge {
        val material =
            try {
                Base64.getDecoder().decode(challenge)
            } catch (malformed: IllegalArgumentException) {
                throw DeviceServiceException.Undecodable(malformed)
            }
        val digest = MessageDigest.getInstance(SHA_256).digest(material)
        return AttestationChallenge.classic(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    }

    /**
     * The `attestation` field's value for a token.
     *
     * The token is already a compact JWS, and the field carries it base64-encoded **on top of that**: standard
     * alphabet with padding, over the token's UTF-8 bytes — not URL-safe, and not the raw token. Sending the
     * token unwrapped passes the field's own validation and fails verification later, which is why the double
     * encoding is stated here rather than left to look redundant.
     */
    fun attestationField(token: AttestationToken): String =
        Base64.getEncoder().encodeToString(token.value.toByteArray(Charsets.UTF_8))

    private const val SHA_256 = "SHA-256"
}
