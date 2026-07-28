package com.payabli.sdk.core.network.impl

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A real HTTP server on loopback, so the transport tests exercise the actual `HttpURLConnection`
 * rather than a hand-written stub of it.
 *
 * `com.sun.net.httpserver` ships with the JDK, so this adds no dependency. A mock web server would be
 * a third-party HTTP library, which the dependency policy bars even in tests.
 */
internal class LoopbackServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private var responder: (HttpExchange) -> Unit = { it.reply(HTTP_OK, "") }

    /** Every request the server received, in order. */
    val recorded: MutableList<Recorded> = mutableListOf()

    init {
        server.createContext("/") { exchange ->
            recorded += exchange.record()
            responder(exchange)
            exchange.close()
        }
        server.start()
    }

    val baseUrl: String get() = "http://${server.address.hostString}:${server.address.port}"

    /** The single request the server received. Fails if it saw none or more than one. */
    val onlyRequest: Recorded get() = recorded.single()

    fun respondWith(block: (HttpExchange) -> Unit): LoopbackServer {
        responder = block
        return this
    }

    fun respondWith(
        statusCode: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): LoopbackServer =
        respondWith { exchange ->
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.reply(statusCode, body)
        }

    override fun close() = server.stop(0)

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

    internal companion object {
        internal const val HTTP_OK: Int = 200

        /** Marks a response that carries no body at all, per `sendResponseHeaders`. */
        internal const val NO_BODY: Long = -1

        internal fun HttpExchange.reply(
            statusCode: Int,
            body: String,
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) {
                sendResponseHeaders(statusCode, NO_BODY)
                return
            }
            sendResponseHeaders(statusCode, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun HttpExchange.record(): Recorded =
            Recorded(
                method = requestMethod,
                path = requestURI.path,
                // rawQuery, not query: getQuery() percent-decodes, which would hide an unencoded
                // separator reaching the wire.
                query = requestURI.rawQuery,
                headers = requestHeaders.mapValues { it.value.toList() },
                body = requestBody.readBytes().toString(Charsets.UTF_8),
            )
    }
}
