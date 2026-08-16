package com.payabli.example.app

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/**
 * The merchant backend the sample app expects, for the length of one run.
 *
 * The app holds no credential and asks a server for a token, so driving it against a real environment needs
 * one. On a bench that is `example-server`; here it is this, so a CI run needs no process beside the test and
 * no port forwarded to the device.
 *
 * The exchange is the one that server performs: `POST {base}/api/v2/token/serverside` with `clientId` and
 * `clientSecret`, reading `accessToken`.
 */
internal class LiveTokenServer(
    private val baseUrl: String,
    private val clientId: String,
    private val clientSecret: String,
) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var minted: String? = null

    @Volatile
    private var failure: Throwable? = null

    val port: Int get() = socket.localPort

    /**
     * What went wrong serving a token, or null.
     *
     * The app reports a failed token step as a form that never unlocked, which names this endpoint nowhere.
     * A caller reads this to say what actually happened instead.
     */
    val servingFailure: Throwable? get() = failure

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use { client ->
                        client.getInputStream().bufferedReader().readLine()
                        // A refused mint answers 500 rather than closing the socket. Closing it reaches the app
                        // as a transport error, which it reports the same way as every other unreachable
                        // server, so the one run that knows why would be the one saying nothing.
                        val response =
                            try {
                                ok(json("accessToken" to token()))
                            } catch (error: Throwable) {
                                failure = error
                                serverError(json("error" to (error.message ?: error.javaClass.simpleName)))
                            }
                        client.getOutputStream().apply {
                            write(response.toByteArray())
                            flush()
                        }
                    }
                }.onFailure { error ->
                    // A socket closed by `close()` ends the loop and is not a failure to report.
                    if (!socket.isClosed) failure = error
                }
            }
        }
    }

    override fun close() = socket.close()

    // One token for the run. The SDK asks again whenever it wants a fresh one, and a mint per ask would
    // spend a live call on a token nothing has finished reading.
    private fun token(): String = minted ?: mint().also { minted = it }

    private fun mint(): String {
        val connection =
            (URL("$baseUrl$TOKEN_PATH").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        return try {
            connection.outputStream.use { out ->
                out.write(credentialJson().toByteArray())
            }
            // getInputStream throws for 4xx and 5xx; the body, when the server sent one, is on errorStream,
            // which is null when it sent none. Reading it first is what makes a refused exchange report its
            // status instead of an IOException naming the URL.
            val status = connection.responseCode
            val stream =
                if (status < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            // Parsed rather than matched. A token is opaque, so an escape sequence in one is not a strange
            // input, and a pattern reading up to the next quote would stop inside the value or run past it.
            // Guarded because an error body is not always the JSON the success path expects, and a parse
            // failure would lose the status this message carries.
            runCatching { Json.parseToJsonElement(body).jsonObject }
                .getOrNull()
                ?.let { payload ->
                    TOKEN_FIELDS.firstNotNullOfOrNull { field ->
                        payload[field]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    }
                }
                ?: error("the token exchange answered HTTP $status without a token")
        } finally {
            connection.disconnect()
        }
    }

    // Built by the serializer rather than by interpolation, in both directions. A credential and a token are
    // both opaque, so a quote or a backslash in either is not a strange input: it would end the string early
    // and what the other side reports is a malformed message rather than the value that broke it.
    private fun credentialJson(): String = json("clientId" to clientId, "clientSecret" to clientSecret)

    private fun json(vararg fields: Pair<String, String>): String =
        Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), fields.toMap())

    private fun ok(body: String) = response("200 OK", body)

    private fun serverError(body: String) = response("500 Internal Server Error", body)

    private fun response(
        status: String,
        body: String,
    ) = "HTTP/1.1 $status\r\n" +
        "Content-Type: application/json\r\n" +
        "Content-Length: ${body.toByteArray().size}\r\n" +
        "Connection: close\r\n\r\n" +
        body

    private companion object {
        const val TOKEN_PATH = "/api/v2/token/serverside"
        const val TIMEOUT_MILLIS = 20_000
        val TOKEN_FIELDS = listOf("accessToken", "access_token", "token")
    }
}
