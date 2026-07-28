package com.payabli.sdk.core.network.impl

import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * [PayabliTransport] over `HttpURLConnection`, the platform HTTP client.
 *
 * Pure transport: it holds no auth state and interprets nothing. `execute` returns a non-2xx response
 * as a [PayabliResponse] rather than an exception, leaving the status to [PayabliHttpErrors]; only the
 * decoding overload maps it, because it has to before committing to a decode. Bearer injection is the
 * authenticated decorator that arrives with the session.
 *
 * Transport and configuration failures do surface as a [PayabliException]: an `IOException` becomes
 * [PayabliErrorCode.NETWORK_ERROR], a malformed URL [PayabliErrorCode.INVALID_CONFIGURATION], and a
 * body that will not decode [PayabliErrorCode.DECODING_ERROR], each preserving the original as its
 * cause.
 *
 * `internal`, and deliberately so: nothing outside `:core` names this type. Capability modules depend
 * on the [PayabliTransport] interface and receive an instance from the session.
 *
 * **The decoration chain is applied here, not by a wrapping layer.** Non-bypassability is a construction
 * property rather than a layering one: a wrapper would leave a working undecorated transport in the graph
 * for anyone in `:core` to pick up, which is the "one forgotten call ships unauthenticated" failure
 * this design exists to prevent. So the constructor is private, [create] is the only production path, and it
 * does not accept a chain — no caller chooses the decorations, so none can choose an empty set.
 */
internal class PayabliService private constructor(
    baseUrl: String,
    private val decorations: List<PayabliRequestDecoration>,
    private val logger: PayabliLogger,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) : PayabliTransport {
    /**
     * Kept with a trailing slash so [URI.resolve] appends to it rather than replacing its last segment.
     *
     * Validated here rather than on first use: a bad base URL is a configuration mistake, and it should
     * surface when the SDK is configured rather than on the first payment.
     */
    private val base: URI =
        try {
            URI.create(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        } catch (e: IllegalArgumentException) {
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_INVALID_URL, cause = e)
        }

    override suspend fun execute(request: PayabliRequest): PayabliResponse =
        withContext(Dispatchers.IO) {
            // The choke-point. First statement, so no path through this method skips it.
            val decorated = decorations.applyTo(request)
            val connection = openConnection(decorated)
            var completed = false
            val startedAt = System.nanoTime()
            try {
                logger.debug(methodField(decorated), routeField(decorated)) { "request" }
                ensureActive()
                writeBody(connection, decorated)
                ensureActive()
                readResponse(connection).also { response ->
                    completed = true
                    logger.debug(
                        methodField(decorated),
                        routeField(decorated),
                        LogField.safe("statusCode", response.statusCode),
                        LogField.safe("durationMs", elapsedMillis(startedAt)),
                        LogField.safe("contentLength", response.body.size),
                    ) { "response" }
                }
            } catch (e: IOException) {
                // Cancellation can surface as an IOException when the socket is torn down under us, so
                // re-check first: it must propagate as cancellation, not as a spurious network error.
                // Only IOException is caught, never Exception: CancellationException is a
                // RuntimeException, and swallowing it would leave a cancelled coroutine looking complete.
                ensureActive()
                logger.error(
                    e,
                    methodField(decorated),
                    routeField(decorated),
                    LogField.safe("errorCode", PayabliErrorCode.NETWORK_ERROR),
                ) { "request failed" }
                throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON_NETWORK_FAILED, cause = e)
            } finally {
                // disconnect() signals that further requests are unlikely, which forfeits the pooled
                // socket, so it is only correct when bailing out with the stream unread.
                if (!completed) connection.disconnect()
            }
        }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> {
        val response = execute(request)
        // Map the status before committing to a decode, so a proxy's HTML error page becomes a typed
        // error rather than a decode failure.
        PayabliHttpErrors.from(response)?.let { throw it }
        return try {
            PayabliJson.format.decodeFromString(
                PayabliV2Envelope.serializer(payloadSerializer),
                response.bodyAsText(),
            )
        } catch (e: SerializationException) {
            // SerializationException extends IllegalArgumentException; catching the supertype would
            // swallow genuine programming errors raised from inside a serializer.
            throw PayabliGenericException(PayabliErrorCode.DECODING_ERROR, REASON_DECODE_FAILED, cause = e)
        }
    }

    /**
     * Wraps its own failures rather than letting the caller do it: this runs before `execute`'s `try`,
     * so anything escaping here would miss both the exception mapping and the `disconnect()` in
     * `finally`.
     */
    private fun openConnection(request: PayabliRequest): HttpURLConnection {
        val url = resolveOrThrow(request)
        val connection =
            try {
                url.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON_NETWORK_FAILED, cause = e)
            }
        connection.requestMethod = request.method.wireName
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        // A payments API does not redirect, and following one would forward the Authorization header
        // to whatever host the 3xx names. A 3xx is returned to the caller instead.
        connection.instanceFollowRedirects = false
        // Accept-Encoding is left unset on purpose. Android's implementation then negotiates gzip and
        // decompresses transparently; setting it by hand would make decompression our problem. The JVM
        // implementation used by unit tests does not do this, so gzip is covered by instrumented tests.
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    /** A bad base URL or path is configuration, not a network failure, so it maps differently. */
    private fun resolveOrThrow(request: PayabliRequest): URL =
        try {
            resolve(request)
        } catch (e: IllegalArgumentException) {
            throw invalidUrl(e)
        } catch (e: URISyntaxException) {
            throw invalidUrl(e)
        } catch (e: MalformedURLException) {
            // An IOException subtype, so it must be caught ahead of the network mapping above.
            throw invalidUrl(e)
        }

    private fun invalidUrl(cause: Throwable): PayabliGenericException =
        PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_INVALID_URL, cause = cause)

    private fun resolve(request: PayabliRequest): URL {
        val resolved = base.resolve(request.path.removePrefix("/")).toString()
        if (request.query.isEmpty()) return URI.create(resolved).toURL()
        val query =
            request.query.joinToString("&") { (name, value) ->
                "${urlEncode(name)}=${urlEncode(value)}"
            }
        return URI.create("$resolved?$query").toURL()
    }

    private fun writeBody(
        connection: HttpURLConnection,
        request: PayabliRequest,
    ) {
        val body = request.body ?: return
        // doOutput is set only when there is a body: setting it on a GET silently rewrites the method
        // to POST. setFixedLengthStreamingMode avoids buffering the body a second time.
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
    }

    private fun readResponse(connection: HttpURLConnection): PayabliResponse {
        val statusCode = connection.responseCode
        // getInputStream throws for 4xx and 5xx; the body, when the server sent one, is on errorStream,
        // which is null when it sent none.
        val stream =
            if (statusCode < HttpURLConnection.HTTP_BAD_REQUEST) {
                connection.inputStream
            } else {
                connection.errorStream
            }
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        return PayabliResponse(statusCode, readHeaders(connection), body)
    }

    /** Drops the null-keyed entry, which carries the status line rather than a header. */
    private fun readHeaders(connection: HttpURLConnection): Map<String, String> =
        buildMap {
            connection.headerFields?.forEach { (name, values) ->
                if (name != null && values != null) put(name, values.joinToString(", "))
            }
        }

    private fun methodField(request: PayabliRequest): LogField = LogField.safe("method", request.method.wireName)

    /** Only an explicit template is loggable; a resolved path may embed an identifier. */
    private fun routeField(request: PayabliRequest): LogField =
        request.route?.let { LogField.safe("route", it) } ?: LogField.redacted("route", request.path)

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    /** The two-argument overload: the `Charset` one is API 33 and this module's floor is 23. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal companion object {
        /** Deliberately generic: these reach a host app, so they carry no host, path or server text. */
        internal const val REASON_NETWORK_FAILED: String = "Network request failed"
        internal const val REASON_DECODE_FAILED: String = "Failed to decode response envelope"
        internal const val REASON_INVALID_URL: String = "Invalid request URL"

        internal const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000

        /**
         * Per-read budget, bounding a single socket read rather than the whole call. `RetryPolicy`'s
         * per-attempt timeout is what bounds the resource.
         */
        internal const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 10_000

        /**
         * The only production way to obtain a transport.
         *
         * It does not take a decoration list, deliberately: no caller anywhere chooses the chain, so no
         * caller can choose an empty one. Returns the interface, so nothing
         * accumulates a dependency on the concrete class.
         */
        internal fun create(
            baseUrl: String,
            logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
            connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
        ): PayabliTransport =
            PayabliService(
                baseUrl,
                PayabliRequestDecorations.chain,
                logger,
                connectTimeoutMillis,
                readTimeoutMillis,
            )

        /**
         * Builds a transport with a caller-supplied chain, so a test can prove [execute] applies whatever
         * chain it holds without a decoration having to exist in production.
         *
         * A named, narrow hole in the guarantee above, and the reason it is acceptable: it widens what a
         * `:core` **test** can construct, not what production can. Do not call it from `src/main`.
         */
        @VisibleForTesting
        internal fun createWithDecorations(
            baseUrl: String,
            decorations: List<PayabliRequestDecoration>,
            logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
        ): PayabliTransport =
            PayabliService(
                baseUrl,
                decorations,
                logger,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS,
            )
    }
}
