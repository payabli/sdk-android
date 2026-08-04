package com.payabli.sdk.taptopay.attestation

import androidx.annotation.RestrictTo
import java.util.Base64

/** Play Integrity's documented ceiling, in characters, for a `nonce` and for a `requestHash` alike. */
private const val MAX_LENGTH = 500

/**
 * Play Integrity's documented floor for a `nonce`, **in decoded bytes**. A `requestHash` has none.
 *
 * Bytes rather than characters because the two Google pages that state this limit disagree, and each is
 * enforced here on the axis its own page names. The guide says "Minimum of 16 characters / Maximum of 500
 * characters" of the encoded string; the `IntegrityErrorCode` reference says `NONCE_TOO_SHORT` means "the
 * nonce must be a minimum of 16 bytes (before base64 encoding)". Sixteen characters of base64 decode to
 * twelve bytes, so a value can satisfy the guide and still be refused by the platform.
 *
 * Taking the stricter reading on each axis costs nothing real: the character floor is implied, since 16
 * bytes never encode to fewer than 22 characters, and the documented byte ceiling is unreachable behind the
 * character one, since 500 characters carry at most 375 bytes. So there is no separate byte-ceiling check,
 * and adding one would be a branch no input can take.
 */
private const val MIN_NONCE_BYTES = 16

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
 * this type**, and nothing here will invent a value for a caller.
 *
 * **That is a speed bump, not an enforcement, and the difference matters.** Both entry points take a
 * `String`, so a caller can pass anything, including something it generated itself. This type cannot tell
 * a server-issued value from a self-issued one, and no client-side type could. Provenance is an invariant
 * held between whoever issues the challenge and whoever verifies the resulting token: the verifier accepts
 * only values it issued and has not yet retired, and that check is what makes freshness real. Omitting a
 * generator removes the easy way to get this wrong; it does not make it impossible.
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
         * URL-safe, non-wrapping base64 that decodes, carrying at least [MIN_NONCE_BYTES] bytes and written
         * in at most [MAX_LENGTH] characters. Each limit is the platform's own and has an error code behind
         * it that this rejection replaces: `NONCE_IS_NOT_BASE64`, `NONCE_TOO_SHORT` and `NONCE_TOO_LONG`.
         */
        public fun classic(nonce: String): AttestationChallenge {
            require(nonce.length <= MAX_LENGTH) { "nonce must be at most $MAX_LENGTH characters" }
            require(URL_SAFE_BASE64.matches(nonce)) {
                "nonce must be URL-safe base64 with no line wrapping"
            }
            // Decoded rather than measured as text: the floor the platform enforces is on the bytes the
            // nonce carries, and the alphabet check above says nothing about whether the string decodes at
            // all. A length that is not a valid base64 length, 501 characters for instance, gets here.
            val decoded =
                runCatching { Base64.getUrlDecoder().decode(nonce) }.getOrElse {
                    throw IllegalArgumentException("nonce is not decodable base64", it)
                }
            require(decoded.size >= MIN_NONCE_BYTES) {
                "nonce must carry at least $MIN_NONCE_BYTES bytes; this one decodes to ${decoded.size}"
            }
            return AttestationChallenge(VerdictClass.CLASSIC, nonce)
        }
    }
}
