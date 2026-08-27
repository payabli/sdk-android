package com.payabli.sdk.telemetry

import com.payabli.sdk.core.PayabliSdkVersion
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.telemetry.wire.TelemetryBatchBody
import com.payabli.sdk.telemetry.wire.TelemetryEventBody
import com.payabli.sdk.telemetry.wire.wireName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends a batch, and lets nothing out.
 *
 * Every failure ends here: a refused batch, an unreachable host, a body too large. The caller is a payment
 * app that asked for nothing, so there is nobody to hand a failure to, and a reporting channel that could
 * surface one would be a reporting channel that can interrupt a payment.
 *
 * **No retry.** A batch that did not arrive is dropped rather than kept, because the reply says only whether
 * the caller was authorized, so a client cannot tell a batch worth resending from one that will be refused
 * exactly the same way forever. Keeping it would trade a lost batch for a queue that never empties.
 */
internal class TelemetryUploader(
    private val transport: PayabliTransport,
    private val context: TelemetrySessionContext,
    private val logger: SdkLogger,
) {
    suspend fun send(
        batch: List<QueuedTelemetryEvent>,
        droppedSinceLastSend: Int = 0,
    ): Boolean {
        if (batch.isEmpty()) return false

        try {
            // Assembly is inside the guard. `PayabliRequest.json` serializes as it builds, so a wire model
            // that cannot encode raises here, and outside the guard that reaches the thread's default handler.
            val body =
                TelemetryBatchBody(
                    entry = context.entryPoint,
                    events = batch.map { it.toBody() },
                    droppedSinceLastSend = droppedSinceLastSend.takeIf { it > 0 },
                )
            val request =
                PayabliRequest.json(
                    method = HttpMethod.POST,
                    path = ROUTE,
                    body = body,
                    bodySerializer = TelemetryBatchBody.serializer(),
                    route = ROUTE,
                    // A rejected credential here must not spend the session's one refresh, and must not be
                    // able to condemn a session a payment is using.
                    isCredentialPinned = true,
                )

            val response = transport.execute(request)
            if (response.isSuccessful) {
                logger.debug(
                    LogField.safe("event", "telemetry_batch_sent"),
                    LogField.safe("route", ROUTE),
                    LogField.safe("statusCode", response.statusCode),
                ) { "sent ${batch.size} events" }
                return true
            } else {
                logger.debug(
                    LogField.safe("event", "telemetry_batch_refused"),
                    LogField.safe("route", ROUTE),
                    LogField.safe("statusCode", response.statusCode),
                ) { "batch not accepted; discarded" }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: PayabliException) {
            logger.warn(
                LogField.safe("event", "telemetry_batch_failed"),
                LogField.safe("route", ROUTE),
                LogField.safe("errorCode", failure.code.wireName),
            ) { "batch could not be sent; discarded" }
        } catch (failure: RuntimeException) {
            // Wider than the transport's documented contract, and that is the point: a reporting channel that
            // can raise on a background coroutine takes the host app down with it, because an uncaught
            // exception there reaches the thread's default handler. Nothing this module does may end a
            // payment app, so anything that escapes the seam above ends here too.
            logger.warn(
                LogField.safe("event", "telemetry_batch_failed"),
                LogField.safe("route", ROUTE),
                LogField.safe("errorKind", failure.javaClass.simpleName),
            ) { "batch could not be sent; discarded" }
        }
        return false
    }

    private fun QueuedTelemetryEvent.toBody(): TelemetryEventBody =
        TelemetryEventBody(
            schemaVersion = TelemetryEventBody.SCHEMA_VERSION,
            sdkVersion = PayabliSdkVersion.VALUE,
            timestamp = formatTimestamp(occurredAtMillis),
            sessionId = session.sessionId,
            entry = session.entryPoint,
            environment = session.environment.wireName(),
            event = name,
            properties = properties,
            deviceIdHash = session.device.idHash.ifBlank { null },
            deviceType = session.device.type.ifBlank { null },
            deviceOs = session.device.os.ifBlank { null },
            osVersion = session.device.osVersion.ifBlank { null },
            modelName = session.device.modelName.ifBlank { null },
            entryHash = session.entryHash.ifBlank { null },
            packageHash = session.device.packageHash.ifBlank { null },
        )

    internal companion object {
        /** The one route this module calls. */
        const val ROUTE: String = "/api/v2/telemetry/sdk"

        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

        /**
         * A formatter per call, rather than a shared one.
         *
         * `SimpleDateFormat` is not thread-safe and a shared instance would corrupt a timestamp under two
         * concurrent flushes, which is a class of bug that leaves no trace beyond a wrong time. `java.time`
         * would be the better tool and needs either API 26 or core library desugaring, and neither is worth a
         * dependency in a published artifact for one formatter run a handful of times a minute.
         *
         * Three fractional digits, always, including when the millisecond is zero, and the zone is fixed to
         * UTC so the trailing `Z` is true. A formatter that dropped trailing zeros would emit a different
         * shape for one event in a thousand, and one that read the device zone would name the wrong instant
         * while looking correct.
         */
        internal fun formatTimestamp(epochMillis: Long): String =
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMillis))
    }
}
