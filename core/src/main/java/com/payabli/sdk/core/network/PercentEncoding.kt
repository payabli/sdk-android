package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo

/** Unreserved characters, per RFC 3986 Section 2.3. Everything else in a segment is encoded. */
private const val UNRESERVED = "-._~"

/**
 * Uppercase, because RFC 3986 Section 6.2.2.1 says a producer writes the hexadecimal digits of a
 * percent-encoding in upper case.
 */
private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * Percent-encoding for the parts of a URL this SDK builds.
 *
 * Here rather than in a capability module because two of them needed the identical function and a
 * capability never depends on a sibling, so the alternative was one copy each. They had one each, and this
 * replaced both: a URL encoder is the wrong thing to keep two of, since the copies drift on which
 * characters they cover and the one that falls behind sends a request nobody wrote.
 *
 * It sits beside [PayabliRequest] because that is where path construction already lives. The transport
 * refuses an authority, a scheme, a foreign origin and a path that escapes the base; this is the rest —
 * the characters that change a request without leaving it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PercentEncoding {
    /**
     * Encodes [value] for use as a single path segment.
     *
     * Keeps the unreserved set of RFC 3986 Section 2.3 and encodes everything else from its UTF-8 bytes, so
     * a `?`, `#` or `/` in an identifier stays part of the identifier instead of becoming a query, a
     * fragment or another route.
     *
     * `URLEncoder` is not this: it writes form encoding, where a space becomes `+` rather than `%20`.
     *
     * **Whether a value is allowed at all is the caller's question, not this one.** An empty [value]
     * encodes to an empty string, which is a different route rather than a refusal, so a caller that
     * cannot accept that checks before calling.
     */
    public fun segment(value: String): String =
        buildString(value.length) {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UNRESERVED) {
                    append(char)
                } else {
                    append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                }
            }
        }
}
