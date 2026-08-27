package com.payabli.sdk.telemetry.wire

import com.payabli.sdk.core.config.PayabliEnvironment
import kotlinx.serialization.Serializable

/**
 * A batch of events, which is what one request carries.
 *
 * [entry] is on both the batch and every event, and the two always agree here. The batch's is what the
 * request is authorized against; an event whose own value differs is dropped, so a client that let them
 * differ would be reporting one merchant's activity under another's.
 */
@Serializable
internal class TelemetryBatchBody(
    val entry: String,
    val events: List<TelemetryEventBody>,
    /**
     * How many events the queue evicted since the last batch left, omitted when none.
     *
     * On the batch rather than in an event, and on a request that is succeeding rather than in one of its
     * own: a report of lost telemetry that travelled as telemetry would be queued by the queue that just
     * overflowed, and would be sent by the channel whose failure caused it. This rides a request that
     * worked, so it says what was lost while it was not working.
     */
    val droppedSinceLastSend: Int? = null,
)

/**
 * One event, as it goes on the wire.
 *
 * [schemaVersion] is a string and not a number. It versions the shape rather than counting anything, and the
 * two platforms have to agree on the encoding as much as on the value.
 *
 * [deviceIdHash] is the digest device registration sends, so a device is one device in both. It and the
 * three fields after it are the fixed device facts every event carries; each is omitted rather than sent
 * blank where the platform offered nothing, because absent and empty are different statements and only one
 * of them is true.
 */
@Serializable
internal class TelemetryEventBody(
    val schemaVersion: String,
    val sdkVersion: String,
    val timestamp: String,
    val sessionId: String,
    val entry: String,
    val environment: String,
    val event: String,
    val properties: Map<String, String>,
    val deviceIdHash: String? = null,
    val deviceType: String? = null,
    val deviceOs: String? = null,
    val osVersion: String? = null,
    val modelName: String? = null,
    val packageName: String? = null,
) {
    companion object {
        /** The only shape this client sends. */
        const val SCHEMA_VERSION: String = "1"
    }
}

/** The reported form of an environment. Exhaustive, so a new one cannot be silently unreportable. */
internal fun PayabliEnvironment.wireName(): String =
    when (this) {
        PayabliEnvironment.QA -> "qa"
        PayabliEnvironment.SANDBOX -> "sandbox"
        PayabliEnvironment.PRODUCTION -> "production"
    }
