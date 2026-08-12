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
 * `HttpURLConnection` and `kotlinx.serialization`, matching the SDK, which admits no third-party
 * HTTP client and no reflection-based JSON mapper.
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
                // That a token arrived is the whole result. The token, a prefix of it and its
                // length are all secret, and a sample app is the last place to teach otherwise.
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
                connection = opened.prepared(method)
                outcomeOf(connection, onSuccess)
            } catch (e: IOException) {
                // The message alone: on a demo screen "Connection refused" is the actionable half,
                // and a stack trace is not.
                TokenServerProbe.Unreachable(e.message ?: e.javaClass.simpleName)
            } catch (e: RuntimeException) {
                // A port out of range reaches here. `toIntOrNull` accepts 99999, so a typed override
                // survives the resolver and the connection rejects it, and not as an IOException:
                // measured on this JVM the throwable is a RuntimeException wrapping
                // `IllegalArgumentException: port out of range:99999`, so catching the cause's type
                // does not catch it. Escaping this block would fail the coroutine with the screen's
                // busy flag still set, and that flag is what makes the operation single flight, so
                // the button would stay disabled until the app was killed.
                //
                // Broad, because this function's contract is that it returns an outcome. A demo
                // screen showing what went wrong beats a control that never works again.
                TokenServerProbe.Unreachable(e.cause?.message ?: e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }

    /** Configured and, for a POST, sent: the flag is what makes `HttpURLConnection` send at all. */
    private fun HttpURLConnection.prepared(method: String): HttpURLConnection =
        apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            if (method == "POST") {
                // The server accepts an empty body on this route.
                doOutput = true
                outputStream.use { it.write(ByteArray(0)) }
            }
        }

    private fun outcomeOf(
        connection: HttpURLConnection,
        onSuccess: (String) -> TokenServerProbe,
    ): TokenServerProbe {
        val code = connection.responseCode
        if (code !in SUCCESS_RANGE) return TokenServerProbe.HttpStatus(code)
        val body =
            connection.inputStream.use { readBounded(it) }
                ?: return TokenServerProbe.Malformed("the body was over $MAX_BODY_BYTES bytes")
        return onSuccess(body)
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
