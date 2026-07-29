package com.payabli.sdk.core.network.impl

import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.ProtocolException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [PayabliTransport] over `HttpURLConnection`, the platform HTTP client.
 *
 * Interprets nothing: `execute` returns a non-2xx response as a [PayabliResponse] rather than an
 * exception, leaving the status to [PayabliHttpErrors]; only the decoding overload maps it, because it
 * has to before committing to a decode. A 401 is a status like any other here, and
 * `AuthenticatedTransport` is what turns one into a refresh and a retry.
 *
 * It holds no auth state of its own. The bearer is stamped by `BearerDecoration` inside the chain, so
 * every request that leaves this class is authenticated and there is no undecorated path to forget.
 *
 * Transport and configuration failures do surface as a [PayabliException]: an `IOException` becomes
 * [PayabliErrorCode.NETWORK_ERROR], a malformed URL [PayabliErrorCode.INVALID_CONFIGURATION], and a
 * body that will not decode [PayabliErrorCode.DECODING_ERROR], each preserving the original as its
 * cause.
 *
 * `internal`, and deliberately so: nothing outside `:core` names this type. Capability modules depend
 * on the [PayabliTransport] interface and receive an instance from the session.
 *
 * **The decoration chain is applied here, not by a wrapping layer.** Decorating in a wrapper would leave a
 * working undecorated transport for a `:core` caller to pick up. So the constructor is private, [create] is
 * the only production path, and it takes no chain.
 *
 * `AuthenticatedTransport` and `Retry` do wrap this, and are control flow rather than decorations: forgetting
 * either loses a retry, never a credential.
 */
internal class PayabliService private constructor(
    baseUrl: String,
    private val decorations: List<PayabliRequestDecoration>,
    private val logger: PayabliLogger,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    /**
     * Budget for one whole call, the counterpart to iOS's `timeoutIntervalForResource`. Needed because
     * [readTimeoutMillis] bounds only the wait for the next byte, so a server dribbling bytes never trips it.
     *
     * A [Duration] rather than millis like its neighbours: those are `Int` because `HttpURLConnection` takes
     * milliseconds, whereas this one is ours and `withTimeout` takes a duration.
     *
     * It bounds **one** exchange, never a sequence of them. That is what keeps it clear of a credential
     * refresh: a refresh is its own call through this class and gets its own budget.
     */
    private val callTimeout: Duration,
    /**
     * Injected rather than hardcoded so a test can substitute one. Held per instance because it
     * describes how this implementation works and never varies per call; putting it on the interface
     * would push the choice onto every caller, which is what main-safety exists to avoid.
     */
    private val dispatcher: CoroutineDispatcher,
    /** Ceiling on a response body, so a hostile or misconfigured server cannot exhaust the app's heap. */
    private val maxResponseBytes: Long,
) : PayabliTransport {
    /**
     * Kept with a trailing slash so [URI.resolve] appends to it rather than replacing its last segment.
     *
     * Validated here rather than on first use: a bad base URL is a configuration mistake, and it should
     * surface when the SDK is configured rather than on the first payment.
     */
    private val base: URI =
        try {
            URI.create(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/").also {
                // Syntax alone is not enough: `file:///tmp` parses, then fails the HttpURLConnection cast
                // with a ClassCastException that no mapping catches.
                require(it.isAbsolute) { "base URL must be absolute" }
                require(it.scheme.lowercase() in ALLOWED_SCHEMES) { "base URL must be http or https" }
                require(!it.host.isNullOrEmpty()) { "base URL must have a host" }
            }
        } catch (e: IllegalArgumentException) {
            // Redacted like the path causes: URI.create echoes its input, and a misconfigured base URL
            // can carry userinfo, so the message could hold `user:password@host`.
            throw invalidUrl(e)
        }

    override suspend fun execute(request: PayabliRequest): PayabliResponse =
        withContext(dispatcher) {
            // The choke-point. First statement, so no path through this method skips it.
            val decorated = decorations.applyTo(request)
            try {
                withTimeout(callTimeout) { exchange(decorated) }
            } catch (e: TimeoutCancellationException) {
                // Converted, not propagated: `Retry` never catches a CancellationException, so this would
                // abandon the whole operation rather than fail one attempt. Checked first, because a caller
                // cancelling at the same moment must stay cancelled instead of reading as a network failure.
                currentCoroutineContext().ensureActive()
                logger.error(
                    e,
                    methodField(decorated),
                    routeField(decorated),
                    LogField.safe("errorCode", PayabliErrorCode.NETWORK_ERROR),
                    LogField.safe("callTimeoutMs", callTimeout.inWholeMilliseconds),
                ) { "call exceeded its timeout" }
                throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON_CALL_TIMED_OUT, cause = e)
            }
        }

    /**
     * One request and one response, bounded by whatever deadline the caller installed.
     *
     * Separate from [execute] so that deadline owns this body's [Job], which is what makes the cancellation
     * handler below fire on a timeout and not only on a caller's cancellation.
     */
    private suspend fun exchange(decorated: PayabliRequest): PayabliResponse {
        val connection = openConnection(decorated)
        var completed = false
        val startedAt = System.nanoTime()
        // Cancelling cannot interrupt a blocking socket read, and readTimeout only bounds the wait for the
        // *next* byte, so a server dribbling bytes outlives any coroutine deadline. Disconnecting from the
        // cancellation handler is what tears the socket down. Documented as effective on Android; the JVM
        // leaves asynchronous close unspecified, so this is not a guarantee there.
        val onCancel =
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) connection.disconnect()
            }
        try {
            logger.debug(methodField(decorated), routeField(decorated)) { "request" }
            currentCoroutineContext().ensureActive()
            writeBody(connection, decorated)
            currentCoroutineContext().ensureActive()
            return readResponse(connection).also { response ->
                // The deadline is only observable here. Everything above blocks a thread rather than
                // suspending, so a coroutine cancelled mid-read reaches this point without noticing and
                // would return a response it is no longer entitled to.
                currentCoroutineContext().ensureActive()
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
            // re-check first: it must propagate as cancellation, not as a spurious network error. That
            // covers the deadline too, which reaches here as a disconnected socket mid-read.
            // Only IOException is caught, never Exception: CancellationException is a
            // RuntimeException, and swallowing it would leave a cancelled coroutine looking complete.
            currentCoroutineContext().ensureActive()
            logger.error(
                e,
                methodField(decorated),
                routeField(decorated),
                LogField.safe("errorCode", PayabliErrorCode.NETWORK_ERROR),
            ) { "request failed" }
            throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON_NETWORK_FAILED, cause = e)
        } finally {
            onCancel?.dispose()
            // disconnect() signals that further requests are unlikely, which forfeits the pooled
            // socket, so it is only correct when bailing out with the stream unread.
            if (!completed) connection.disconnect()
        }
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = execute(request).asV2Envelope(payloadSerializer)

    /**
     * Wraps its own failures rather than letting the caller do it: this runs before `execute`'s `try`,
     * so anything escaping here would miss both the exception mapping and the `disconnect()` in
     * `finally`.
     */
    private fun openConnection(request: PayabliRequest): HttpURLConnection {
        val url = resolveOrThrow(request)
        val connection =
            try {
                url.openConnection() as? HttpURLConnection
                    ?: throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_INVALID_URL)
            } catch (e: IOException) {
                throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON_NETWORK_FAILED, cause = e)
            }
        try {
            connection.requestMethod = request.method.wireName
        } catch (e: ProtocolException) {
            // Caught ahead of IOException, which it extends. An unsupported verb is configuration, not a
            // network fault. Note PATCH: Android's implementation accepts it, the JVM's does not, so a
            // PATCH route works on a device and cannot be exercised by a JVM unit test.
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_METHOD_UNSUPPORTED, cause = e)
        }
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

    /**
     * A bad base URL or path is configuration, not a network failure, so it maps differently.
     *
     * Every cause here is redacted, because all three shapes can carry the rejected path. A
     * `URISyntaxException` echoes its input verbatim, and the `IllegalArgumentException` from
     * `URI.create` at the query step carries the whole resolved URL, query values included. Type is
     * therefore not a way to tell a safe cause from an unsafe one, so none is trusted.
     */
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
        PayabliGenericException(
            PayabliErrorCode.INVALID_CONFIGURATION,
            REASON_INVALID_URL,
            cause = RedactedCause(cause),
        )

    /**
     * Resolves [PayabliRequest.path] against the configured base, pinned to the base's origin.
     *
     * `URI.resolve` returns the reference unchanged when it is already absolute, and takes the
     * reference's authority when it has one, so an unchecked path could redirect a request — headers and
     * all — to another host. Both a syntactic reject and an origin comparison, because the second stays
     * correct if the first is ever loosened.
     */
    private fun resolve(request: PayabliRequest): URL {
        val reference = request.path
        require(!reference.startsWith("//")) { "path must not carry an authority" }
        require(URI(reference).scheme == null) { "path must be relative to the configured base URL" }

        val resolved = base.resolve(reference.removePrefix("/"))
        require(sameOrigin(resolved)) { "path must not leave the configured origin" }
        require(
            resolved
                .normalize()
                .path
                .orEmpty()
                .startsWith(base.path.orEmpty()),
        ) {
            "path must not escape the configured base path"
        }

        if (request.query.isEmpty()) return resolved.toURL()
        val query =
            request.query.joinToString("&") { (name, value) ->
                "${urlEncode(name)}=${urlEncode(value)}"
            }
        return URI.create("$resolved?$query").toURL()
    }

    private fun sameOrigin(candidate: URI): Boolean =
        candidate.scheme.equals(base.scheme, ignoreCase = true) &&
            candidate.host.equals(base.host, ignoreCase = true) &&
            candidate.port == base.port

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
        val body = stream?.use { readBounded(it) } ?: ByteArray(0)
        return PayabliResponse(statusCode, readHeaders(connection), body)
    }

    /**
     * Reads at most [maxResponseBytes], failing rather than growing without limit: an unbounded read lets a
     * misconfigured or hostile server exhaust the host app's heap before any status mapping runs.
     *
     * Hand-rolled because there is no usable alternative at this module's floor. `InputStream.readNBytes`
     * is the right primitive but it is API 33, and it is absent from R8's desugar configuration, so
     * desugaring cannot backfill it. Do not "simplify" this to `readBytes()`.
     */
    private fun readBounded(stream: InputStream): ByteArray {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxResponseBytes) {
                throw PayabliGenericException(
                    PayabliErrorCode.NETWORK_ERROR,
                    REASON_RESPONSE_TOO_LARGE,
                    detail = "limit $maxResponseBytes bytes",
                )
            }
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    /** Drops the null-keyed entry, which carries the status line rather than a header. */
    private fun readHeaders(connection: HttpURLConnection): Map<String, String> {
        // Bound to a read-only type first: the platform hands back a Map whose mutability Kotlin cannot
        // prove, and nothing here mutates it.
        // Nullable key and value on purpose: the platform really does return a null-keyed entry for the
        // status line, so a non-null type here would compile and then admit a null key at runtime.
        val fields: Map<String?, List<String>?> = connection.headerFields ?: return emptyMap()
        return buildMap {
            fields.forEach { (name, values) ->
                if (name != null && values != null) put(name, values.joinToString(", "))
            }
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
        internal const val REASON_INVALID_URL: String = "Invalid request URL"
        internal const val REASON_RESPONSE_TOO_LARGE: String = "Response body exceeded the allowed size"
        internal const val REASON_METHOD_UNSUPPORTED: String = "HTTP method not supported on this platform"
        internal const val REASON_CALL_TIMED_OUT: String = "Network request exceeded its timeout"

        private val ALLOWED_SCHEMES = setOf("http", "https")

        /** Generous for a JSON API and far below anything that would strain a device. */
        internal const val DEFAULT_MAX_RESPONSE_BYTES: Long = 10L * 1024 * 1024
        private const val READ_CHUNK_BYTES: Int = 8 * 1024

        internal const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000

        /** Per-read budget, bounding a single socket read rather than the whole call. */
        internal const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 10_000

        /**
         * Three times the per-read budget, landing on the same 10s and 30s pair iOS sets as
         * `timeoutIntervalForRequest` and `timeoutIntervalForResource`. Room for a few slow reads, not for an
         * indefinite dribble.
         */
        internal val DEFAULT_CALL_TIMEOUT: Duration = (3 * DEFAULT_READ_TIMEOUT_MILLIS).milliseconds

        /**
         * The only production way to obtain a transport.
         *
         * It does not take a decoration list, deliberately: no caller anywhere chooses the chain, so no
         * caller can choose an empty one. Returns the interface, so nothing
         * accumulates a dependency on the concrete class.
         *
         * [auth] is what the chain needs, not what this transport interprets: it reaches
         * `BearerDecoration` and nothing else here reads it. Passing the holder rather than a token is what
         * lets the bearer be read per request, so a rotation needs no cache to be invalidated.
         */
        internal fun create(
            baseUrl: String,
            auth: PayabliAuth,
            logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
            connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
            callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        ): PayabliTransport =
            PayabliService(
                baseUrl,
                PayabliRequestDecorations.chainFor(auth),
                logger,
                connectTimeoutMillis,
                readTimeoutMillis,
                callTimeout,
                dispatcher,
                maxResponseBytes,
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
            callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        ): PayabliTransport =
            PayabliService(
                baseUrl,
                decorations,
                logger,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS,
                callTimeout,
                dispatcher,
                maxResponseBytes,
            )
    }
}
