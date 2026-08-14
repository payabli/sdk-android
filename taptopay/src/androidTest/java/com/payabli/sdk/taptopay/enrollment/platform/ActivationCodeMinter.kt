package com.payabli.sdk.taptopay.enrollment.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mints the six-digit code, playing the merchant's part.
 *
 * The SDK has no method for this: a component that could mint its own code could activate itself. This sits
 * in the test source set, past the publish boundary.
 *
 * A bare `HttpURLConnection`, not the SDK's transport, which would leave this one refactor from becoming a
 * client method. The route takes `pos_create`, the same permission the device calls already need, so the
 * run's one token serves here too. In production this call comes from the merchant's backend under its own
 * credential.
 *
 * Called mid-sequence: the route needs a device handle, which exists only after registration.
 */
internal object ActivationCodeMinter {
    private val FORMAT = Json { ignoreUnknownKeys = true }

    fun mint(
        baseUrl: String,
        accessToken: String,
        entry: String,
        deviceId: String,
    ): String {
        val connection =
            (URL("$baseUrl/api/v2/device/taptopay/activate/challenge").openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.outputStream.use {
                it.write("""{"entry":"$entry","deviceId":"$deviceId"}""".toByteArray(Charsets.UTF_8))
            }

            val body =
                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.readText()
                        .orEmpty()
                }

            val envelope = FORMAT.parseToJsonElement(body).jsonObject
            if (envelope["isSuccess"]?.jsonPrimitive?.content != "true") {
                // The harness echoes the service's reason. A person running this needs it spelled out, and
                // nothing here ships.
                error("could not mint an activation code: $body")
            }
            envelope["responseData"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.content
                ?: error("the activation-challenge response carried no code: $body")
        } finally {
            connection.disconnect()
        }
    }
}
