package com.payabli.sdk.core

/**
 * The device identifier a session reports, from the one the platform offered.
 *
 * @param identifier the identifier the platform offered, or a blank when it offered none.
 * @return [identifier], or null when it is blank.
 */
internal fun sessionDeviceId(identifier: String): String? = identifier.ifBlank { null }
