package com.payabli.example.app.net

import com.payabli.example.app.config.TokenServerTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    /** The socket work moves off the caller's thread. A test substitutes its own. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
                        // `content` renders a number or a boolean as text, so `{"accessToken":true}`
                        // would read back as the token "true" and this route would report healthy.
                        ?.takeIf { it.isString }
                        ?.content
                }.getOrNull()
            when {
                token.isNullOrBlank() -> TokenServerProbe.Malformed("the body carried no token")
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
        withContext(ioDispatcher) {
            var connection: HttpURLConnection? = null
            try {
                // The launch override takes any value carrying a scheme, so `file://` or `ftp://`
                // opens a connection that is not an `HttpURLConnection`. A cast throws
                // `ClassCastException`, which is not an `IOException` and so passes the handler
                // below and takes the probe down. This puts a line on the screen instead.
                val opened = URL(url).openConnection()
                if (opened !is HttpURLConnection) {
                    return@withContext TokenServerProbe.Unreachable(
                        "$url is not an http or https address.",
                    )
                }
                connection =
                    opened.apply {
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
                    val body = connection.inputStream.use { readBounded(it) }
                    if (body == null) {
                        TokenServerProbe.Malformed("the body was over $MAX_BODY_BYTES bytes")
                    } else {
                        onSuccess(body)
                    }
                }
            } catch (e: IOException) {
                // The message alone: on a demo screen "Connection refused" is the actionable half,
                // and a stack trace is not.
                TokenServerProbe.Unreachable(e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Reads at most [MAX_BODY_BYTES], returning null rather than growing without limit.
     *
     * An unbounded read lets a misconfigured or hostile token host exhaust the app's heap before any
     * of this ever looks at the body, which is the reasoning `PayabliService.readBounded` records for
     * the same shape in the SDK's own transport. Hand-rolled for the same reason it is there:
     * `InputStream.readNBytes` is the right primitive and it is API 33, above this module's floor.
     *
     * The limit is generous against what this route returns, which is one JSON object carrying one
     * token, so reaching it means the host is not the token server.
     */
    private fun readBounded(stream: InputStream): String? {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) return null
            sink.write(buffer, 0, read)
        }
        return sink.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
        const val MAX_BODY_BYTES = 64 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
        val SUCCESS_RANGE = 200..299
    }
}
