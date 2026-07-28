package com.payabli.sdk.core.network.impl

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

private const val CRLF = "\r\n"
private const val HTTP_OK = 200
private const val EPHEMERAL_PORT = 0
private const val DEFAULT_BACKLOG = 0
private const val READ_TIMEOUT_MILLIS = 5_000
private const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L

/**
 * A real HTTP server on loopback, so the transport tests exercise the actual `HttpURLConnection`
 * rather than a hand-written stub of it.
 *
 * Built on [ServerSocket] rather than `com.sun.net.httpserver`, which is absent from `android.jar`:
 * that package neither resolves in the IDE nor runs in an instrumented test, and the Android-only
 * transport behaviours can only be shown by an instrumented test. A mock web server would be a
 * third-party HTTP library, which the dependency policy bars even in tests.
 *
 * Parses only what the transport sends: a request line, headers, and a `Content-Length` body.
 * Chunked encoding fails loudly rather than reading an empty body and passing.
 */
internal class LoopbackServer : AutoCloseable {
    private val socket = ServerSocket(EPHEMERAL_PORT, DEFAULT_BACKLOG, InetAddress.getLoopbackAddress())
    private val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile
    private var responder: Response = Response(HTTP_OK, ByteArray(0))

    @Volatile
    private var failure: Throwable? = null

    private val worker =
        Thread({ serve() }, "loopback-server").apply {
            isDaemon = true
            start()
        }

    val baseUrl: String get() = "http://${socket.inetAddress.hostAddress}:${socket.localPort}"

    /** Every request the server received, in order. */
    val recorded: List<Recorded> get() = requests

    /** The single request the server received. Fails if it saw none or more than one. */
    val onlyRequest: Recorded get() = requests.single()

    fun respondWith(
        statusCode: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): LoopbackServer {
        responder = Response(statusCode, body.toByteArray(Charsets.UTF_8), headers)
        return this
    }

    override fun close() {
        socket.close()
        worker.join(SHUTDOWN_TIMEOUT_MILLIS)
        // Surface a harness bug here: it would otherwise reach the test as an opaque IOException from
        // the client side. Kotlin's `use` records this as suppressed, so a real assertion still wins.
        failure?.let { throw AssertionError("loopback server failed to serve a request", it) }
    }

    private fun serve() {
        while (true) {
            val connection =
                try {
                    socket.accept()
                } catch (_: IOException) {
                    return // close() was called, which is the only way this thread ends
                }
            try {
                connection.soTimeout = READ_TIMEOUT_MILLIS
                val request = readRequest(BufferedInputStream(connection.getInputStream()))
                if (request != null) {
                    requests += request
                    connection.getOutputStream().apply {
                        write(responder.encode())
                        flush()
                    }
                    connection.shutdownOutput()
                }
            } catch (_: IOException) {
                // The client hanging up mid-response is expected: the response-ceiling tests abandon
                // the read and disconnect, which breaks the pipe under us.
            } catch (t: Throwable) {
                failure = failure ?: t
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    private fun readRequest(input: InputStream): Recorded? {
        val requestLine = input.readAsciiLine() ?: return null
        val parts = requestLine.split(' ')
        require(parts.size >= 2) { "malformed request line: $requestLine" }
        val target = parts[1]
        val separator = target.indexOf('?')

        val headers = linkedMapOf<String, MutableList<String>>()
        while (true) {
            val line = input.readAsciiLine()
            if (line.isNullOrEmpty()) break
            val colon = line.indexOf(':')
            require(colon > 0) { "malformed header line: $line" }
            headers.getOrPut(line.take(colon)) { mutableListOf() } += line.substring(colon + 1).trim()
        }
        require(headers.keys.none { it.equals("Transfer-Encoding", ignoreCase = true) }) {
            "this harness reads Content-Length bodies only, which is all setFixedLengthStreamingMode sends"
        }

        return Recorded(
            method = parts[0],
            path = if (separator < 0) target else target.take(separator),
            // The raw target, never URI-decoded: decoding would hide an unencoded separator on the wire.
            query = if (separator < 0) null else target.substring(separator + 1),
            headers = headers.mapValues { it.value.toList() },
            body = String(input.readExactly(headers.contentLength()), Charsets.UTF_8),
        )
    }

    internal class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
    }

    private class Response(
        private val statusCode: Int,
        private val body: ByteArray,
        private val headers: Map<String, String> = emptyMap(),
    ) {
        fun encode(): ByteArray =
            buildString {
                // The reason phrase is not read back by any test, so one neutral token serves every code.
                append("HTTP/1.1 ").append(statusCode).append(" Status").append(CRLF)
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append(CRLF) }
                append("Content-Length: ").append(body.size).append(CRLF)
                // Closed after every response, so the client must not return this socket to its pool.
                append("Connection: close").append(CRLF).append(CRLF)
            }.toByteArray(Charsets.ISO_8859_1) + body
    }
}

private fun Map<String, List<String>>.contentLength(): Int =
    entries
        .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.toInt()
        ?: 0

/** Header bytes are ISO-8859-1 per RFC 9110, so each byte maps straight to a char. */
private fun InputStream.readAsciiLine(): String? {
    val line = StringBuilder()
    while (true) {
        val byte = read()
        if (byte < 0) return if (line.isEmpty()) null else line.toString()
        if (byte == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(byte.toChar())
    }
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val progress = read(bytes, read, count - read)
        if (progress < 0) throw IOException("request body ended after $read of $count bytes")
        read += progress
    }
    return bytes
}
