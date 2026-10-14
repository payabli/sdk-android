package com.payabli.sdk.core.config

private const val FIRST_PRINTABLE = ' '
private const val LAST_PRINTABLE = '~'

/**
 * True when every character can legally sit in an HTTP header value. A carriage return or
 * line feed would be header injection, and `setRequestProperty` rejects illegal characters
 * with an unchecked exception that escapes before the transport can map it.
 *
 * @return true when every character can legally sit in an HTTP header value; false otherwise.
 */
internal fun String.isHeaderSafe(): Boolean = all { it in FIRST_PRINTABLE..LAST_PRINTABLE }
