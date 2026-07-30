package com.payabli.sdk.core.config

private const val FIRST_PRINTABLE = ' '
private const val LAST_PRINTABLE = '~'

/**
 * True when every character can legally sit in an HTTP header value.
 *
 * A token reaches the wire as `Authorization: Bearer <token>`. A carriage return or line feed in it would be
 * header injection, and `HttpURLConnection.setRequestProperty` rejects illegal characters with an unchecked
 * `IllegalArgumentException` that escapes before the transport can map it, so the caller sees the wrong
 * exception type for the wrong reason.
 *
 * Printable US-ASCII only, which is what a bearer credential is made of. Checked where a token enters rather
 * than where it is stamped, so a bad one is never installed.
 */
internal fun String.isHeaderSafe(): Boolean = all { it in FIRST_PRINTABLE..LAST_PRINTABLE }
