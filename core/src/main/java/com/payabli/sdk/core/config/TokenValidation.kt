package com.payabli.sdk.core.config

private const val FIRST_PRINTABLE = ' '
private const val LAST_PRINTABLE = '~'

/**
 * True when every character is printable US-ASCII, `' '` through `'~'` — narrower than a header
 * may carry (a tab is legal there and refused here), because a bearer credential is made of
 * nothing else. A carriage return or line feed would be header injection, and `setRequestProperty` throws unchecked before the transport can map it.
 *
 * @return true when every character is printable US-ASCII; false otherwise.
 */
internal fun String.isHeaderSafe(): Boolean = all { it in FIRST_PRINTABLE..LAST_PRINTABLE }
