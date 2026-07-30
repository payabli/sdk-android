package com.payabli.sdk.core.network.impl

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

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
    /**
     * Pinned to the IPv4 loopback rather than `getLoopbackAddress()`, which lets the platform pick the
     * family: this Android emulator answers `::1` where the JVM answers `127.0.0.1`. That difference is not
     * cosmetic. An IPv6 literal is not a URL authority unbracketed, so the base URL parses to no host and
     * the transport rejects it as invalid configuration, and the bracketed form then has to be matched by a
     * cleartext exemption keyed on the literal. Choosing the family here keeps both problems from existing.
     */
    private val socket = ServerSocket(EPHEMERAL_PORT, DEFAULT_BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile
    private var responder: Response = Response(HTTP_OK, ByteArray(0))

    /** Non-empty means scripted: entry N answers request N, and the last entry answers everything after. */
    @Volatile
    private var script: List<Response> = emptyList()

    /** Set means the response is chosen from the request rather than from its position. */
    @Volatile
    private var chooser: ((Recorded) -> Response)? = null

    @Volatile
    private var stallMillis: Long = 0

    /** Non-zero means the body is written in chunks with this gap between them. */
    @Volatile
    private var dribbleGapMillis: Long = 0

    /** Set means the body is deflated and labelled, so the client has to undo it. */
    @Volatile
    private var compressResponses = false

    /**
     * Body bytes of the last response as they went onto the wire, so a test can show compression happened
     * rather than assume it. Asserting only on the decoded body cannot: an uncompressed round trip produces
     * the same result, so the test would still pass with compression switched off.
     */
    @Volatile
    var lastResponseBodyBytes: Int = -1
        private set

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

    /**
     * Scripts one response per request, in order, for a test whose subject is a sequence: a 401 followed
     * by a 200 is not expressible with a single canned response.
     *
     * **The last entry answers every request after it, deliberately.** Running out and hanging would make
     * "no third attempt" fail as a timeout instead of as a count, and a test that hangs is not a test that
     * goes in. So an extra attempt gets served and the assertion on [recorded] size is what fails.
     */
    fun respondInOrder(vararg responses: Pair<Int, String>): LoopbackServer {
        require(responses.isNotEmpty()) { "a script needs at least one response" }
        script = responses.map { (status, body) -> Response(status, body.toByteArray(Charsets.UTF_8), emptyMap()) }
        return this
    }

    /**
     * Chooses the response from the request, so a test with concurrent callers does not depend on the order
     * they happen to arrive in. Prefer this over [respondInOrder] whenever more than one caller is in flight:
     * a positional script silently encodes an interleaving the scheduler does not promise.
     */
    fun respondPerRequest(choose: (Recorded) -> Pair<Int, String>): LoopbackServer {
        chooser = { request ->
            val (status, body) = choose(request)
            Response(status, body.toByteArray(Charsets.UTF_8), emptyMap())
        }
        return this
    }

    /**
     * Reads the request, then waits [millis] before answering, for a test whose subject is a deadline.
     *
     * A stall rather than silence: a server that never answers is indistinguishable from one that is slow,
     * and the socket-level read timeout would eventually end either. Set this above the deadline under test
     * and below that read timeout, so the deadline is provably what fired.
     */
    fun stallBeforeResponding(millis: Long): LoopbackServer {
        stallMillis = millis
        return this
    }

    /**
     * Sends the body one byte at a time with [gapMillis] between bytes, so a peer makes slow but continuous
     * progress. This is the case a socket read timeout cannot catch: every individual read completes well
     * inside it, so only a whole-call bound can end the exchange.
     */
    fun dribbleBody(gapMillis: Long): LoopbackServer {
        dribbleGapMillis = gapMillis
        return this
    }

    /**
     * Deflates the body and labels it `Content-Encoding: gzip`, with `Content-Length` counting the
     * compressed bytes as a real server would.
     *
     * Only meaningful in an instrumented test. The transport leaves `Accept-Encoding` unset so the platform
     * can negotiate and decompress on its own; Android's implementation does that, the JVM's does not, so on
     * the JVM this delivers compressed bytes the caller never asked for and would have to undo by hand.
     */
    fun gzipBody(): LoopbackServer {
        compressResponses = true
        return this
    }

    /** By request if a chooser is set, otherwise by position. */
    private fun responseFor(
        index: Int,
        request: Recorded,
    ): Response = chooser?.invoke(request) ?: script.getOrNull(index) ?: script.lastOrNull() ?: responder

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
                    // Index before appending, so the first request reads entry 0.
                    val answer = responseFor(requests.size, request)
                    requests += request
                    // Recorded before stalling, so a test can assert the request arrived even when the
                    // client gave up waiting for its response.
                    if (stallMillis > 0) Thread.sleep(stallMillis)
                    val encoded = answer.encode(compressResponses)
                    lastResponseBodyBytes = encoded.bodyBytes
                    connection.getOutputStream().apply {
                        if (dribbleGapMillis > 0) {
                            for (byte in encoded.bytes) {
                                write(byte.toInt())
                                flush()
                                Thread.sleep(dribbleGapMillis)
                            }
                        } else {
                            write(encoded.bytes)
                            flush()
                        }
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
        /** The wire bytes, paired with the body's own length so the caller can record what it sent. */
        class Encoded(
            val bytes: ByteArray,
            val bodyBytes: Int,
        )

        fun encode(compress: Boolean): Encoded {
            val payload = if (compress) gzip(body) else body
            val head =
                buildString {
                    // The reason phrase is not read back by any test, so one neutral token serves every code.
                    append("HTTP/1.1 ").append(statusCode).append(" Status").append(CRLF)
                    headers.forEach { (name, value) -> append(name).append(": ").append(value).append(CRLF) }
                    if (compress) append("Content-Encoding: gzip").append(CRLF)
                    // The compressed length, as a real server sends: a client that decompresses transparently
                    // must not trust this as the size of what it hands back.
                    append("Content-Length: ").append(payload.size).append(CRLF)
                    // Closed after every response, so the client must not return this socket to its pool.
                    append("Connection: close").append(CRLF).append(CRLF)
                }.toByteArray(Charsets.ISO_8859_1)
            return Encoded(head + payload, payload.size)
        }
    }
}

private fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { sink -> GZIPOutputStream(sink).use { it.write(bytes) } }.toByteArray()

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
