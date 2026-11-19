package com.payabli.sdk.taptopay.enrollment.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches an access token from the demo token server in `example-server`, which holds the client secret so the
 * app never does. It is reached over `adb reverse tcp:8787 tcp:8787`, and the live tier also hands this call to
 * the SDK as its refresh provider, so a run that outlives its token recovers.
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

            tokenIn(connection.inputStream.bufferedReader().readText())
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

    /**
     * The token in a token server's answer. A malformed body is reported without its text or cause, which can
     * quote the token.
     *
     * @param body the answer's body.
     * @return the access token it carries.
     * @throws IllegalStateException when the body is not an object carrying a token.
     */
    fun tokenIn(body: String): String =
        try {
            val json = FORMAT.parseToJsonElement(body).jsonObject
            (json["accessToken"] ?: json["access_token"])?.jsonPrimitive?.content
        } catch (_: IllegalArgumentException) {
            null
        } ?: error("the token server answered without a token")

    private const val TIMEOUT_MILLIS = 10_000
}
