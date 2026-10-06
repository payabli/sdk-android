package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.PayabliSdkVersion
import com.payabli.sdk.core.network.PayabliRequest
import java.util.Locale

internal const val CLIENT_HEADER: String = "X-Pyb-Client"

private const val PLATFORM = "android"

/**
 * What `X-Pyb-Client` reports about this client. A null or blank value is reported as absent.
 *
 * The locale and the device identity are read on every request: the locale can change while the app runs, and
 * an identity that could not be read at install can be read later.
 */
internal class ClientFacts(
    val sdkVersion: String,
    val osVersion: String,
    val hardware: String,
    val deviceId: () -> String?,
    val locale: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    companion object {
        /** No device to ask, which is the SDK's own tests on a JVM. */
        val NONE: ClientFacts = ClientFacts(PayabliSdkVersion.VALUE, "", "", deviceId = { null })
    }
}

/**
 * The `X-Pyb-Client` value: an RFC 9651 dictionary whose members are joined by `", "`.
 *
 * The order is fixed, so the same facts are always the same octets: `sdk-version`, `platform`, `os-version`,
 * `hardware`, `locale`, then `device-id`. `platform` is a token and every other member is a string. A member
 * whose value is blank, or carries a character outside printable US-ASCII, is left out rather than transcoded.
 */
internal object ClientHeader {
    fun valueOf(facts: ClientFacts): String =
        listOfNotNull(
            string("sdk-version", facts.sdkVersion),
            "platform=$PLATFORM",
            string("os-version", facts.osVersion),
            string("hardware", facts.hardware),
            string("locale", facts.locale()),
            string("device-id", facts.deviceId()),
        ).joinToString(", ")

    private fun string(
        name: String,
        value: String?,
    ): String? {
        if (value.isNullOrBlank() || value.any { it !in ' '..'~' }) return null
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "$name=\"$escaped\""
    }
}

/** Stamps `X-Pyb-Client` onto every outbound request, replacing any value a caller set. */
internal class ClientHeaderDecoration(
    private val facts: ClientFacts,
) : PayabliRequestDecoration {
    override suspend fun decorate(request: PayabliRequest): PayabliRequest =
        request.withHeaders(mapOf(CLIENT_HEADER to ClientHeader.valueOf(facts)))
}
