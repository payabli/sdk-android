package com.payabli.sdk.taptopay.attestation

import androidx.annotation.RestrictTo

/** Play Integrity's documented ceiling, for a `nonce` and for a `requestHash` alike. */
private const val MAX_LENGTH = 500

/** Play Integrity's documented floor for a `nonce`. A `requestHash` has none. */
private const val MIN_NONCE_LENGTH = 16

/**
 * URL-safe base64, non-wrapping, with padding optional.
 *
 * `+` and `/` are the standard alphabet's two characters and are rejected here; `-` and `_` replace them.
 * Padding is accepted rather than required because both `NO_PADDING` and padded encoders are in ordinary
 * use and Play Integrity accepts either, so rejecting one would refuse a value the platform would have
 * taken. A newline is what "non-wrapping" excludes, and the character class has no room for one.
 */
private val URL_SAFE_BASE64 = Regex("^[A-Za-z0-9_-]+={0,2}$")

/**
 * The single-use, **server-issued** value an attestation is bound to.
 *
 * Freshness is the whole point of the value, and freshness a client mints is not freshness: a value the
 * device chose proves only that the device can choose values. So there is deliberately **no generator on
 * this type**. The only way to obtain a challenge is to be handed one, and the absence of a factory that
 * would make one up is the enforcement, not the documentation.
 *
 * Which field it lands in depends on [verdictClass]: `requestHash` for [VerdictClass.STANDARD], `nonce`
 * for [VerdictClass.CLASSIC]. The two carry different validity rules, which is why there are two
 * constructors and no way to build one without saying which.
 *
 * **Shape is rejected here, before the platform sees it.** Play Integrity answers a malformed value with
 * an error code, several rounds and one service call later; the same defect is a rejected argument at the
 * call site instead. `IllegalArgumentException`, matching how a malformed storage key is treated, because
 * a caller cannot recover from a value it built wrong.
 *
 * Single use is **not** enforced here. A value is a value, and nothing about it says whether it has been
 * spent; that ledger belongs to whatever performs the attestation, and it is enforced there.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class AttestationChallenge private constructor(
    /** Which request shape this is bound to, and therefore which field it lands in. */
    public val verdictClass: VerdictClass,
    /**
     * The value itself, passed to the platform verbatim.
     *
     * Not secret, and Play Integrity is explicit that a `nonce` reaches Google in cleartext, so it must
     * never be built from anything sensitive. It is still not logged: it is the one thing that makes a
     * given attestation un-replayable, and a log is a longer-lived place than the request.
     */
    internal val value: String,
) {
    /** Never the value: a challenge in a log or a stack trace is a challenge with a second lifetime. */
    override fun toString(): String = "AttestationChallenge(verdictClass=$verdictClass)"

    public companion object {
        /**
         * A challenge for a standard request, landing in `requestHash`.
         *
         * At most [MAX_LENGTH], counted in characters **and** in UTF-8 bytes, because the platform
         * documents the ceiling both ways and the stricter reading is the one that cannot surprise a
         * caller at run time.
         *
         * There is no lower bound and no alphabet rule, which is the platform's position: a `requestHash`
         * is an opaque digest of whatever the request meant, so its shape is the caller's business.
         */
        public fun standard(requestHash: String): AttestationChallenge {
            require(requestHash.isNotEmpty()) { "requestHash must not be empty" }
            require(requestHash.length <= MAX_LENGTH) {
                "requestHash must be at most $MAX_LENGTH characters"
            }
            require(requestHash.toByteArray(Charsets.UTF_8).size <= MAX_LENGTH) {
                "requestHash must be at most $MAX_LENGTH bytes as UTF-8"
            }
            return AttestationChallenge(VerdictClass.STANDARD, requestHash)
        }

        /**
         * A challenge for a classic request, landing in `nonce`.
         *
         * [MIN_NONCE_LENGTH] to [MAX_LENGTH] characters of URL-safe, non-wrapping base64. All three are
         * the platform's own limits, and each has an error code behind it that this rejection replaces.
         */
        public fun classic(nonce: String): AttestationChallenge {
            require(nonce.length >= MIN_NONCE_LENGTH) {
                "nonce must be at least $MIN_NONCE_LENGTH characters"
            }
            require(nonce.length <= MAX_LENGTH) { "nonce must be at most $MAX_LENGTH characters" }
            require(URL_SAFE_BASE64.matches(nonce)) {
                "nonce must be URL-safe base64 with no line wrapping"
            }
            return AttestationChallenge(VerdictClass.CLASSIC, nonce)
        }
    }
}
