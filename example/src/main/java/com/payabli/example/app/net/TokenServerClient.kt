package com.payabli.example.app.net

import com.payabli.example.app.config.TokenServerTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the local token server in `example-server/`.
 *
 * `HttpURLConnection` and `kotlinx.serialization`. The SDK admits no third-party HTTP client or
 * reflection-based JSON mapper, and a sample app that reached for one would be showing integrators
 * the wrong thing.
 *
 * This is the app fetching its own token, not an SDK call. When the SDK arrives, this is roughly what
 * goes behind its token provider. The provider must mint on every call, which is why the route here is
 * `exchange-token`.
 */
class TokenServerClient(
    private val target: TokenServerTarget,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Asks for a token and reports whether one came back. The token itself is never returned. */
    suspend fun probeAccessToken(): TokenServerProbe =
        request(target.accessTokenUrl, method = "POST") { body ->
            val token =
                runCatching {
                    json
                        .parseToJsonElement(body)
                        .jsonObject["accessToken"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            when {
                token.isNullOrBlank() -> TokenServerProbe.HttpStatus(HttpURLConnection.HTTP_OK)
                // Deliberately not the token, not a prefix of it, and not its length: a token is
                // secret, and a sample app is the last place that should teach otherwise. That it
                // arrived is the whole result.
                else -> TokenServerProbe.Ok("returned a token")
            }
        }

    suspend fun probeHealth(): TokenServerProbe =
        request(target.healthUrl, method = "GET") { TokenServerProbe.Ok("healthy") }

    private suspend fun request(
        url: String,
        method: String,
        onSuccess: (String) -> TokenServerProbe,
    ): TokenServerProbe =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection =
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = TIMEOUT_MILLIS
                        readTimeout = TIMEOUT_MILLIS
                        // The server accepts an empty body on this route; the flag is what makes
                        // HttpURLConnection send the request at all for a POST.
                        if (method == "POST") doOutput = true
                    }
                if (method == "POST") {
                    connection.outputStream.use { it.write(ByteArray(0)) }
                }
                val code = connection.responseCode
                if (code !in SUCCESS_RANGE) {
                    TokenServerProbe.HttpStatus(code)
                } else {
                    onSuccess(connection.inputStream.bufferedReader().use { it.readText() })
                }
            } catch (e: IOException) {
                // The message alone: on a demo screen "Connection refused" is the actionable half,
                // and a stack trace is not.
                TokenServerProbe.Unreachable(e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
        val SUCCESS_RANGE = 200..299
    }
}
