package com.payabli.example.app

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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

    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use { client ->
                        client.getInputStream().bufferedReader().readLine()
                        val body = """{"accessToken":"${token()}"}"""
                        client.getOutputStream().apply {
                            write(response(body).toByteArray())
                            flush()
                        }
                    }
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
            val payload = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            TOKEN_FIELD
                .find(payload)
                ?.groupValues
                ?.get(1)
                ?: error("the token exchange answered HTTP $status without a token")
        } finally {
            connection.disconnect()
        }
    }

    // Built by the serializer rather than by interpolation. A credential is opaque, so a quote or a backslash
    // in one is not a strange input: it would end the string early and the exchange would refuse a request
    // that says nothing about why.
    private fun credentialJson(): String =
        Json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            mapOf("clientId" to clientId, "clientSecret" to clientSecret),
        )

    private fun response(body: String) =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" +
            body

    private companion object {
        const val TOKEN_PATH = "/api/v2/token/serverside"
        const val TIMEOUT_MILLIS = 20_000
        val TOKEN_FIELD = """"(?:accessToken|access_token|token)"\s*:\s*"([^"]+)"""".toRegex()
    }
}
