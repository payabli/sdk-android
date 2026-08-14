package com.payabli.sdk.taptopay.enrollment.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a fresh access token from the partner-backend stand-in on the development machine.
 *
 * The counterpart of the sibling demo's token endpoint, and the same server: `LocalTokenServer`, port 8787,
 * path `/payabli/access-token`. It performs the client-credentials exchange that a partner's own backend
 * would, so no client secret is ever in the app.
 *
 * **Reached over `adb reverse`, not the machine's hostname.** Run
 * `adb -s <serial> reverse tcp:8787 tcp:8787` before the live tier, and the device's own loopback forwards
 * to the Mac. The sibling platform has no equivalent and resolves a `.local` name instead, which depends on
 * mDNS and on the device sharing a network with the machine.
 *
 * A token from here is short-lived. The live tier fetches one at start and hands this same call to the SDK
 * as its refresh provider, so a run outliving its token recovers instead of failing at whatever call it had
 * reached.
 */
internal object LocalTokenServer {
    private val FORMAT = Json { ignoreUnknownKeys = true }

    const val DEFAULT_ENDPOINT: String = "http://127.0.0.1:8787/payabli/access-token"

    fun fetch(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS

            if (connection.responseCode !in 200..299) {
                error(
                    "the token server at $endpoint answered ${connection.responseCode}. " +
                        "Start it on the development machine and run: adb reverse tcp:8787 tcp:8787",
                )
            }

            val body = connection.inputStream.bufferedReader().readText()
            val json = FORMAT.parseToJsonElement(body).jsonObject
            (json["accessToken"] ?: json["access_token"])?.jsonPrimitive?.content
                ?: error("the token server answered without a token")
        } catch (unreachable: java.io.IOException) {
            throw IllegalStateException(
                "could not reach the token server at $endpoint. Start it on the development machine and " +
                    "run: adb reverse tcp:8787 tcp:8787",
                unreachable,
            )
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MILLIS = 10_000
}
