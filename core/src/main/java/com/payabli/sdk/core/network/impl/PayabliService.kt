package com.payabli.sdk.core.network.impl

import androidx.annotation.VisibleForTesting
import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.error
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliSendMark
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [PayabliTransport] over `HttpURLConnection`. Interprets nothing: `execute` returns a non-2xx as a
 * [PayabliResponse], leaving the status to [PayabliHttpErrors]; only the decoding overload maps it. An
 * `IOException` becomes NETWORK_ERROR and a malformed URL INVALID_CONFIGURATION, each keeping its cause.
 *
 * @property decorations the chain applied to every request here rather than by a wrapper, so no undecorated path exists.
 * @property callTimeout the budget for one whole exchange, never a sequence: a refresh is its own call with its own budget.
 * @property dispatcher where the blocking work runs, injected so a test can substitute one; per instance, never on the interface.
 * @property maxResponseBytes the ceiling on a response body, so a hostile or misconfigured server cannot exhaust the app's heap.
 */
internal class PayabliService private constructor(
    baseUrl: String,
    private val decorations: List<PayabliRequestDecoration>,
    private val logger: SdkLogger,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val callTimeout: Duration,
    private val dispatcher: CoroutineDispatcher,
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
            // Nothing catches a CancellationException here, so cancellation passes through. Result
            // distinguishes a timeout from a call that returned normally.
            val outcome = withTimeoutOrNull(callTimeout) { Result.success(exchange(decorated)) }
            if (outcome == null) {
                // Converted rather than propagated: a `Retry` above never catches cancellation, so a timeout
                // left as one would end the whole operation instead of one attempt. Cancellation can also land
                // between the null and this throw, and must win over reporting a timeout.
                currentCoroutineContext().ensureActive()
                logger.error(
                    methodField(decorated),
                    routeField(decorated),
                    LogField.safe("errorCode", PayabliErrorType.NETWORK_ERROR),
                    LogField.safe("callTimeoutMs", callTimeout.inWholeMilliseconds),
                ) { "call exceeded its timeout" }
                throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, REASON_CALL_TIMED_OUT)
            }
            outcome.getOrThrow()
        }

    /**
     * One request and one response, under whatever deadline the caller installed.
     * `suspendCancellableCoroutine`, not `Job.invokeOnCompletion`: a completion handler runs only once this
     * body finishes, and a body parked in a blocking read never finishes. The blocking work stays inline.
     *
     * @param decorated the request with its chain applied.
     * @return the response the request answered with.
     */
    private suspend fun exchange(decorated: PayabliRequest): PayabliResponse {
        // Before the platform call, so a late mark can only err towards a send that may have left.
        currentCoroutineContext()[PayabliSendMark]?.mark()
        val connection = openConnection(decorated)

        // Exactly once, not idempotently. The cancellation handler runs on the canceller's thread and the
        // failure path below runs on the calling one, so both can reach it, and the platform documents neither
        // idempotency nor the effect on a blocked read. Nothing here may rely on a second call being free.
        val disconnected = AtomicBoolean(false)
        val disconnectOnce = { if (disconnected.compareAndSet(false, true)) connection.disconnect() }

        return suspendCancellableCoroutine { continuation ->
            // Before any blocking work, so no read can begin without a teardown path.
            continuation.invokeOnCancellation { disconnectOnce() }

            // runCatching also captures a CancellationException, which would normally be a defect. It is
            // benign here: the only source is throwIfCancelled, and a resume after cancellation is dropped,
            // so cancellation still wins over any value or error this produces.
            val outcome = runCatching { exchangeBlocking(decorated, connection, continuation) }

            // Forfeits the pooled socket, so it is only correct when bailing out with the stream unread.
            if (outcome.isFailure) disconnectOnce()
            continuation.resumeWith(outcome)
        }
    }

    /**
     * Blocking by design, and never suspends. Activity is read off [continuation] rather than the coroutine
     * context, because this runs inside the cancellable block where no suspension point exists.
     *
     * @param decorated the request with its chain applied.
     * @param connection the connection this exchange owns.
     * @param continuation the continuation whose activity is read between each blocking step.
     * @return the response the request answered with.
     */
    private fun exchangeBlocking(
        decorated: PayabliRequest,
        connection: HttpURLConnection,
        continuation: CancellableContinuation<PayabliResponse>,
    ): PayabliResponse {
        val startedAt = System.nanoTime()
        try {
            logger.debug(methodField(decorated), routeField(decorated)) { "request" }
            throwIfCancelled(continuation)
            writeBody(connection, decorated)
            throwIfCancelled(continuation)
            return readResponse(connection).also { response ->
                // For the race where the read completes just as cancellation lands, so a cancelled call
                // cannot return a response it is no longer entitled to. Not covered by a test and not
                // coverable: removing it leaves the suite green, because with the teardown working the read
                // throws instead of completing, and the window is too narrow to schedule.
                throwIfCancelled(continuation)
                logger.debug(
                    methodField(decorated),
                    routeField(decorated),
                    LogField.safe("statusCode", response.statusCode),
                    LogField.safe("durationMs", elapsedMillis(startedAt)),
                    LogField.safe("contentLength", response.body.size),
                ) { "response" }
            }
        } catch (e: IOException) {
            // The deadline's disconnect arrives here as a torn-down socket, so re-check first: it must
            // propagate as cancellation rather than be logged and mapped as a spurious network error.
            // Only IOException is caught, never Exception, since swallowing a CancellationException would
            // leave a cancelled call looking complete.
            throwIfCancelled(continuation)
            logger.error(
                e,
                methodField(decorated),
                routeField(decorated),
                LogField.safe("errorCode", PayabliErrorType.NETWORK_ERROR),
            ) { "request failed" }
            throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, REASON_NETWORK_FAILED, cause = e)
        }
    }

    /**
     * Named apart from `kotlinx.coroutines.ensureActive`, imported here, which reads a context instead.
     *
     * @param continuation the continuation whose activity is read.
     */
    private fun throwIfCancelled(continuation: CancellableContinuation<*>) {
        if (!continuation.isActive) throw CancellationException(REASON_EXCHANGE_CANCELLED)
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = execute(request).asV2Envelope(payloadSerializer)

    /**
     * Wraps its own failures rather than letting the caller do it: this runs before `execute`'s `try`,
     * so anything escaping here would miss both the exception mapping and the `disconnect()`.
     *
     * @param request the request to open the connection for.
     * @return the connection, with method, timeouts and headers set.
     */
    private fun openConnection(request: PayabliRequest): HttpURLConnection {
        val url = resolveOrThrow(request)
        val connection =
            try {
                url.openConnection() as? HttpURLConnection
                    ?: throw PayabliGenericException(PayabliErrorType.INVALID_CONFIGURATION, REASON_INVALID_URL)
            } catch (e: IOException) {
                throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, REASON_NETWORK_FAILED, cause = e)
            }
        try {
            connection.requestMethod = request.method.wireName
        } catch (e: ProtocolException) {
            // Caught ahead of IOException, which it extends. An unsupported verb is configuration, not a
            // network fault. Note PATCH: Android's implementation accepts it, the JVM's does not, so a
            // PATCH route works on a device and cannot be exercised by a JVM unit test.
            throw PayabliGenericException(PayabliErrorType.INVALID_CONFIGURATION, REASON_METHOD_UNSUPPORTED, cause = e)
        }
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        // A payments API does not redirect, and following one would forward the Authorization header
        // to whatever host the 3xx names. A 3xx is returned to the caller instead.
        connection.instanceFollowRedirects = false
        // Accept-Encoding is left unset. Android's implementation then negotiates gzip and decompresses
        // transparently; setting it by hand would leave decompression to this code. The JVM
        // implementation used by unit tests does not do this, so gzip is covered by instrumented tests.
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    /**
     * A bad base URL or path is configuration, not a network failure. Every cause here is redacted,
     * because all three shapes can carry the rejected path: a `URISyntaxException` echoes its input and
     * the `IllegalArgumentException` carries the whole resolved URL, so no type is trusted.
     *
     * @param request the request whose path is resolved.
     * @return the resolved URL.
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
            PayabliErrorType.INVALID_CONFIGURATION,
            REASON_INVALID_URL,
            cause = RedactedCause(cause),
        )

    /**
     * Resolves [PayabliRequest.path] against the configured base, pinned to the base's origin:
     * `URI.resolve` returns an absolute reference unchanged and takes the reference's authority, so an
     * unchecked path could redirect a request — headers and all — to another host.
     *
     * @param request the request whose path and query are resolved.
     * @return the resolved URL, origin-checked and within the base path.
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
     * Reads at most [maxResponseBytes], failing rather than growing without limit. Hand-rolled because
     * `InputStream.readNBytes` is API 33 and absent from R8's desugar configuration, so desugaring cannot
     * backfill it. Do not "simplify" this to `readBytes()`.
     *
     * @param stream the body's stream, whichever of input or error it came from.
     * @return the body's bytes, bounded.
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
                    PayabliErrorType.NETWORK_ERROR,
                    REASON_RESPONSE_TOO_LARGE,
                    detail = "limit $maxResponseBytes bytes",
                )
            }
            sink.write(buffer, 0, read)
        }
        return sink.toByteArray()
    }

    /**
     * Drops the null-keyed entry, which carries the status line rather than a header.
     *
     * @param connection the answered connection.
     * @return the headers, each name's values joined, the status-line entry dropped.
     */
    private fun readHeaders(connection: HttpURLConnection): Map<String, String> {
        // Bound to a read-only type first: the platform hands back a Map whose mutability Kotlin cannot
        // prove, and nothing here mutates it.
        // Nullable key and value: the platform returns a null-keyed entry for the
        // status line, so a non-null type here would compile and then admit a null key at runtime.
        val fields: Map<String?, List<String>?> = connection.headerFields ?: return emptyMap()
        return buildMap {
            fields.forEach { (name, values) ->
                if (name != null && values != null) put(name, values.joinToString(", "))
            }
        }
    }

    private fun methodField(request: PayabliRequest): LogField = LogField.safe("method", request.method.wireName)

    /**
     * Only an explicit template is loggable; a resolved path may embed an identifier.
     *
     * @param request the request whose route template is logged.
     * @return a safe field carrying the template, or a redacted one when the request carried no template.
     */
    private fun routeField(request: PayabliRequest): LogField =
        request.route?.let { LogField.safe("route", it) } ?: LogField.redacted("route", request.path)

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    /**
     * The two-argument overload: the `Charset` one is API 33 and this module's floor is 23.
     *
     * @param value the query name or value to encode.
     * @return the value, form-encoded.
     */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal companion object {
        /** Generic, because these reach a host app: they carry no host, path or server text. */
        internal const val REASON_NETWORK_FAILED: String = "Network request failed"
        internal const val REASON_INVALID_URL: String = "Invalid request URL"
        internal const val REASON_RESPONSE_TOO_LARGE: String = "Response body exceeded the allowed size"
        internal const val REASON_METHOD_UNSUPPORTED: String = "HTTP method not supported on this platform"
        internal const val REASON_CALL_TIMED_OUT: String = "Network request exceeded its timeout"
        private const val REASON_EXCHANGE_CANCELLED: String = "the exchange was cancelled"

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
         * The only production way to obtain a transport. It takes no decoration list — no caller anywhere
         * chooses the chain, so no caller can choose an empty one — and returns the interface. The
         * socket-level timeouts are not parameters: the whole-call bound is the one worth varying.
         *
         * @param baseUrl the origin every request goes to.
         * @param auth the holder `BearerDecoration` reads per request, so a rotation needs no cache.
         * @param dispatcher where the blocking exchange runs.
         * @param logger where each request and response is logged.
         * @param callTimeout the budget for one whole call.
         * @param maxResponseBytes the ceiling on a response body.
         * @param client what every request reports about this device.
         * @return the transport, as the interface, with the chain applied.
         */
        internal fun create(
            baseUrl: String,
            auth: PayabliAuth,
            dispatcher: CoroutineDispatcher,
            logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
            callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
            maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
            client: ClientFacts,
        ): PayabliTransport =
            PayabliService(
                baseUrl,
                RequestDecorationFactory.chainFor(auth, client),
                logger,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS,
                callTimeout,
                dispatcher,
                maxResponseBytes,
            )

        /**
         * Builds a transport with a caller-supplied chain, so a test can prove [execute] applies whatever
         * chain it holds without a decoration having to exist in production. A named, narrow hole: it
         * widens what a `:core` test can construct, not what production can. Do not call it from `src/main`.
         *
         * @param baseUrl the origin every request goes to.
         * @param decorations the chain to apply, supplied by the test.
         * @param dispatcher where the blocking exchange runs.
         * @param logger where each request and response is logged.
         * @param callTimeout the budget for one whole call.
         * @param maxResponseBytes the ceiling on a response body.
         * @return the transport, as the interface, holding the supplied chain.
         */
        @VisibleForTesting
        internal fun createWithDecorations(
            baseUrl: String,
            decorations: List<PayabliRequestDecoration>,
            dispatcher: CoroutineDispatcher,
            logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
            callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
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
