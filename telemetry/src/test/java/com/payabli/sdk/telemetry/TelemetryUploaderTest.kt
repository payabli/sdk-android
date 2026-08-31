package com.payabli.sdk.telemetry

import com.payabli.sdk.core.PayabliSdkVersion
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.telemetry.wire.wireName
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUploaderTest {
    private val logger = RecordingSdkLogger()

    private val context =
        TelemetrySessionContext(
            entryPoint = "an-entry-point",
            environment = PayabliEnvironment.SANDBOX,
            telemetryEnabled = true,
            sessionId = "0f8d2a1c-4b6e-4a2f-9c3d-5e7f8a9b0c1d",
            device = aDevice(),
        )

    /**
     * The whole wire shape in one assertion.
     *
     * Every rule this body is held to is enforced by dropping the event in silence, so a change that broke one
     * would leave a client that reports nothing and says nothing. That makes a literal the right assertion
     * here: it fails on the character that changed rather than on the field somebody thought to check.
     */
    @Test
    fun theBatchIsTheShapeTheFarSideAccepts() =
        runTest {
            val transport = FakeTransport()
            val uploader = TelemetryUploader(transport, context, logger)

            uploader.send(
                listOf(
                    QueuedTelemetryEvent(
                        name = TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                        properties = mapOf(TelemetryProperty.OUTCOME.key to "approved"),
                        occurredAtMillis = 1_755_000_000_000,
                        session = context,
                    ),
                ),
            )

            assertEquals(
                """{"entry":"an-entry-point","events":[{"schemaVersion":"1",""" +
                    """"sdkVersion":"${PayabliSdkVersion.VALUE}",""" +
                    """"timestamp":"2025-08-12T12:00:00.000Z","sessionId":""" +
                    """"0f8d2a1c-4b6e-4a2f-9c3d-5e7f8a9b0c1d","entry":"an-entry-point",""" +
                    """"environment":"sandbox","event":"payin.capture.completed",""" +
                    """"properties":{"outcome":"approved"},""" +
                    """"deviceIdHash":"9f2c4b7e1a05d38c6e4b90f7c2a1d5e3","deviceType":"Softpos",""" +
                    """"deviceOs":"Android","osVersion":"14","modelName":"Pixel 7a"}]}""",
                transport.bodyAsText(),
            )
        }

    @Test
    fun theRequestNamesItsRouteAndDeclinesCredentialRecovery() =
        runTest {
            val transport = FakeTransport()

            TelemetryUploader(transport, context, logger).send(listOf(anEvent()))

            val request = transport.sent.single()
            assertEquals(HttpMethod.POST, request.method)
            assertEquals(TelemetryUploader.ROUTE, request.path)
            assertEquals(TelemetryUploader.ROUTE, request.route)
            // A rejected credential here must not spend the session's one refresh, and must not be able to
            // condemn a session a payment is using.
            assertTrue(request.isCredentialPinned)
        }

    /**
     * The session id is on every event, not once per request.
     *
     * It is what ties a run together — one SDK lifetime, one id, across device routes, payments, the quota
     * signal and initialization alike. A batch that put it at the envelope level, or on some of its events,
     * leaves the rest unattributable to the run that produced them. The live and on-device tests assert this too and both
     * skip without a device or an endpoint, so it is pinned here where an ordinary run reaches it.
     */
    @Test
    fun everyEventInABatchCarriesTheSessionId() =
        runTest {
            val transport = FakeTransport()
            val batch =
                listOf(
                    anEvent(),
                    QueuedTelemetryEvent(
                        name = TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                        properties = mapOf(TelemetryProperty.OUTCOME.key to "approved"),
                        occurredAtMillis = 1_755_000_000_001,
                        session = context,
                    ),
                    QueuedTelemetryEvent(
                        name = TelemetryEvents.TTP_DEVICE_ATTEST_COMPLETED,
                        properties = emptyMap(),
                        occurredAtMillis = 1_755_000_000_002,
                        session = context,
                    ),
                )

            TelemetryUploader(transport, context, logger).send(batch)

            val body = transport.bodyAsText()
            assertEquals(
                "one session id per event, and no event without one",
                batch.size,
                body.split(""""sessionId":"${context.sessionId}"""").size - 1,
            )
        }

    /**
     * Which app, beside which merchant, since one entry point serves several of them.
     *
     * Both in the clear. The entry point is a public identifier, which Payabli's own embedded components put
     * in browser JavaScript, and it rides the batch envelope regardless because that is what authorizes the
     * request; a digest beside it would carry nothing the batch does not already say.
     */
    @Test
    fun theApplicationIsCarriedBesideTheEntryPoint() =
        runTest {
            val transport = FakeTransport()
            val installed =
                TelemetrySessionContext(
                    entryPoint = "an-entry-point",
                    environment = PayabliEnvironment.SANDBOX,
                    telemetryEnabled = true,
                    sessionId = "0f8d2a1c-4b6e-4a2f-9c3d-5e7f8a9b0c1d",
                    device =
                        TelemetryDeviceContext(
                            idHash = DEVICE,
                            type = "Softpos",
                            os = "Android",
                            osVersion = "14",
                            modelName = "Pixel 7a",
                            packageName = "com.payabli.example.app",
                        ),
                )

            TelemetryUploader(transport, installed, logger).send(listOf(anEvent(installed)))

            val body = transport.bodyAsText()
            assertTrue(body, body.contains(""""entry":"an-entry-point""""))
            assertTrue(body, body.contains(""""packageName":"com.payabli.example.app""""))
        }

    /**
     * A device that gave the platform nothing is absent from the field, not blank in it.
     *
     * Absent and empty are different statements and only one of them is true: the SDK does not have an
     * identifier for this device, rather than having one that is the empty string.
     */
    @Test
    fun aBlankDeviceIdentifierIsOmittedRatherThanSentEmpty() =
        runTest {
            val transport = FakeTransport()
            val unidentified =
                TelemetrySessionContext(
                    entryPoint = "an-entry-point",
                    environment = PayabliEnvironment.SANDBOX,
                    telemetryEnabled = true,
                    sessionId = "0f8d2a1c-4b6e-4a2f-9c3d-5e7f8a9b0c1d",
                    device = TelemetryDeviceContext.NONE,
                )

            TelemetryUploader(transport, unidentified, logger).send(listOf(anEvent(unidentified)))

            val body = transport.bodyAsText()
            listOf("deviceIdHash", "deviceType", "deviceOs", "osVersion", "modelName", "packageName")
                .forEach { field ->
                    assertFalse("$field was sent by a run with no device: $body", body.contains(field))
                }
        }

    /**
     * The fixed fields are fixed, and a caller cannot reach them.
     *
     * Everything below the event name is added underneath the call, so an emitting site cannot omit one, and
     * a property named like one of them does not become one: properties are their own object on the wire.
     */
    @Test
    fun theDeviceFactsAreOnEveryEventAndNotReachableAsProperties() =
        runTest {
            val transport = FakeTransport()
            val event =
                QueuedTelemetryEvent(
                    name = TelemetryEvents.SDK_INITIALIZED,
                    properties = mapOf(TelemetryProperty.STATE.key to "ready"),
                    occurredAtMillis = 1_755_000_000_000,
                    session = context,
                )

            TelemetryUploader(transport, context, logger).send(listOf(event, event))

            val body = transport.bodyAsText()
            mapOf(
                "deviceType" to "Softpos",
                "deviceOs" to "Android",
                "osVersion" to "14",
                "modelName" to "Pixel 7a",
            ).forEach { (field, value) ->
                assertEquals(
                    "$field should be on both events exactly once each",
                    2,
                    body.split(""""$field":"$value""").size - 1,
                )
            }
            assertTrue(body.contains(""""properties":{"state":"ready"}"""))
        }

    @Test
    fun everyEventInABatchCarriesTheDeviceIdentifier() =
        runTest {
            val transport = FakeTransport()
            val batch = listOf(anEvent(), anEvent(), anEvent())

            TelemetryUploader(transport, context, logger).send(batch)

            assertEquals(
                "one device id per event, matching what registration sends",
                batch.size,
                transport.bodyAsText().split(""""deviceIdHash":"$DEVICE"""").size - 1,
            )
        }

    @Test
    fun anEmptyBatchIsNotSent() =
        runTest {
            val transport = FakeTransport()

            TelemetryUploader(transport, context, logger).send(emptyList())

            assertTrue(transport.sent.isEmpty())
        }

    @Test
    fun arefusedBatchIsDiscardedWithoutReachingTheCaller() =
        runTest {
            val transport = FakeTransport.refusing(statusCode = 401)

            TelemetryUploader(transport, context, logger).send(listOf(anEvent()))

            assertEquals(1, transport.sent.size)
        }

    @Test
    fun atransportFailureIsDiscardedWithoutReachingTheCaller() =
        runTest {
            TelemetryUploader(FakeTransport.failing(), context, logger).send(listOf(anEvent()))
        }

    @Test
    fun everyEnvironmentHasAReportableName() {
        assertEquals(
            listOf("qa", "sandbox", "production"),
            PayabliEnvironment.entries.map { it.wireName() },
        )
    }

    private companion object {
        const val DEVICE = "9f2c4b7e1a05d38c6e4b90f7c2a1d5e3"

        fun aDevice() =
            TelemetryDeviceContext(
                idHash = DEVICE,
                type = "Softpos",
                os = "Android",
                osVersion = "14",
                modelName = "Pixel 7a",
            )
    }

    private fun anEvent(session: TelemetrySessionContext = context) =
        QueuedTelemetryEvent(
            name = TelemetryEvents.SDK_INITIALIZED,
            properties = mapOf(TelemetryProperty.STATE.key to "ready"),
            occurredAtMillis = 1_755_000_000_000,
            session = session,
        )

    /**
     * A body that cannot be encoded is contained, not raised.
     *
     * `PayabliRequest.json` serializes as it builds, so assembling the request outside the guard put a
     * `RuntimeException` on the background coroutine that calls this, where it reaches the thread's default
     * handler and ends the host app. Nothing this module does may end a payment app.
     *
     * The unencodable value is reached through Java's map rather than through any input a call site can
     * produce: what is under test is the boundary, and a guard wider than the failures known today is worth
     * having only if tomorrow's does not have to be one of them.
     */
    @Test
    fun aBodyThatCannotBeEncodedDoesNotEscape() =
        runTest {
            val transport = FakeTransport()
            val uploader = TelemetryUploader(transport, context, logger)

            @Suppress("UNCHECKED_CAST")
            val unencodable =
                java.util.LinkedHashMap<String?, String>().apply { put(null, "a-value") } as Map<String, String>

            val sent =
                uploader.send(
                    listOf(
                        QueuedTelemetryEvent(
                            name = TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                            properties = unencodable,
                            occurredAtMillis = 1_755_000_000_000,
                            session = context,
                        ),
                    ),
                )

            assertFalse("an unsendable batch reported as sent", sent)
            assertTrue("the request was assembled and sent anyway", transport.sent.isEmpty())
        }
}
