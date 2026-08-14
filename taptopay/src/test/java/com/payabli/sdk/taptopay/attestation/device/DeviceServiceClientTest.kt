package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.taptopay.attestation.AttestationToken
import com.payabli.sdk.taptopay.attestation.impl.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val ENTRY = "a-test-entrypoint"

/**
 * Every value distinct and self-naming.
 *
 * These calls take five to seven same-typed strings, so a transposed pair compiles silently. Values that say
 * which parameter they came from turn that into a failing assertion instead of a wrong request nobody notices.
 */
private const val HARDWARE_ID = "hardware-id-value"
private const val KEY_ID = "key-id-value"
private const val DEVICE_ID = "device-id-value"
private const val APP_ID = "com.partner.app"
private const val CHALLENGE_ID = "challenge-id-value"
private const val TOKEN = "header.payload.signature"

/** What TOKEN must look like on the wire: standard base64 of its UTF-8 bytes, computed independently. */
private const val ENCODED_TOKEN = "aGVhZGVyLnBheWxvYWQuc2lnbmF0dXJl"
private const val PUBLIC_KEY = "public-key-value"

/** One plausible `responseData` per route, in the order the routes are called. */
private val ROUTE_PAYLOADS =
    linkedMapOf(
        DeviceServiceClient.ROUTE_CHALLENGE to """{"challengeId":"c","challenge":"Y2g="}""",
        DeviceServiceClient.ROUTE_REGISTER to """{"deviceId":"d","status":"pending"}""",
        DeviceServiceClient.ROUTE_ATTEST to """{"registered":true,"isSandbox":false}""",
        DeviceServiceClient.ROUTE_ACTIVATE to """{"deviceId":"d","status":"active"}""",
    )

private fun identity() = DeviceIdentity(deviceId = DEVICE_ID, keyId = KEY_ID, publicKey = PUBLIC_KEY)

private fun assertion() =
    DeviceAssertion(
        assertion = "assertion-value",
        keyId = "assertion-key-id",
        deviceId = "assertion-device-id",
        timestamp = "2026-08-04T12:00:00.000+0000",
    )

class DeviceServiceClientTest {
    private val logger = RecordingSdkLogger()

    private fun clientFor(transport: FakeDeviceTransport) = DeviceServiceClient(transport, logger)

    private fun FakeDeviceTransport.bodyJson(): JsonObject =
        PayabliJson.format.parseToJsonElement(requestBody).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `challenge posts the entry to the challenge route`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(
                    successEnvelope("""{"challengeId":"c-1","challenge":"Y2g="}"""),
                )

            clientFor(transport).challenge(ENTRY)

            assertEquals(HttpMethod.POST, transport.request.method)
            assertEquals("/api/v2/device/taptopay/challenge", transport.request.path)
            assertEquals(setOf("entry"), transport.bodyJson().keys)
            assertEquals(ENTRY, transport.bodyJson().text("entry"))
        }

    @Test
    fun `challenge reads both fields of the response`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(
                    successEnvelope(
                        """{"challengeId":"challenge-id-from-server","challenge":"Y2hhbGxlbmdlLW1hdGVyaWFs"}""",
                    ),
                )

            val response = clientFor(transport).challenge(ENTRY)

            assertEquals("challenge-id-from-server", response.challengeId)
            assertEquals("Y2hhbGxlbmdlLW1hdGVyaWFs", response.challenge)
        }

    @Test
    fun `every route sends its own template so a log can name the endpoint`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = everyRouteCalled()

            // None of the four embeds an identifier, so template and path are the same string. Asserted rather
            // than assumed, because a null route costs every record in this family the endpoint's name, and
            // `path` is the one form the transport may never log.
            assertEquals(ROUTE_PAYLOADS.keys.toList(), transport.requests.map { it.route })
            assertEquals(transport.requests.map { it.path }, transport.requests.map { it.route })
        }

    @Test
    fun `every route pins the credential it was sent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = everyRouteCalled()

            // The service records the token that attested a device and requires that same one afterwards, so a
            // refresh started by one of these calls breaks the device until it re-attests. Compared per route
            // rather than with `all`, so a route that lost the flag is named by the failure.
            assertEquals(
                ROUTE_PAYLOADS.keys.associateWith { true },
                transport.requests.associate { it.route.orEmpty() to it.isCredentialPinned },
            )
        }

    /** Calls all four routes once, in order, against a transport that answers each one plausibly. */
    private suspend fun everyRouteCalled(): FakeDeviceTransport {
        val transport =
            FakeDeviceTransport {
                // getValue rather than a default, so a route this client sends that the test did not script
                // fails here by name instead of decoding an empty body somewhere later.
                val body = successEnvelope(ROUTE_PAYLOADS.getValue(it.route.orEmpty()))
                PayabliResponse(200, body = body.toByteArray(Charsets.UTF_8))
            }
        val client = clientFor(transport)

        client.challenge(ENTRY)
        client.register(ENTRY, HARDWARE_ID, KEY_ID, null, null, null)
        client.attest(ENTRY, CHALLENGE_ID, identity(), APP_ID, AttestationToken(TOKEN))
        client.activate(ENTRY, DEVICE_ID, "123456", assertion())

        return transport
    }

    @Test
    fun `register sends every field it was given, each under its own key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(successEnvelope("""{"deviceId":"d","status":"pending"}"""))

            clientFor(transport).register(
                entry = ENTRY,
                hardwareId = HARDWARE_ID,
                keyId = KEY_ID,
                deviceName = "device-name-value",
                model = "model-value",
                osVersion = "os-version-value",
            )

            val body = transport.bodyJson()
            assertEquals(
                setOf("entry", "hardwareId", "keyId", "deviceName", "model", "osVersion", "platform"),
                body.keys,
            )
            assertEquals(ENTRY, body.text("entry"))
            assertEquals(HARDWARE_ID, body.text("hardwareId"))
            assertEquals(KEY_ID, body.text("keyId"))
            assertEquals("device-name-value", body.text("deviceName"))
            assertEquals("model-value", body.text("model"))
            assertEquals("os-version-value", body.text("osVersion"))
            assertEquals("Android", body.text("platform"))
        }

    @Test
    fun `register omits the descriptive fields it was not given`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(successEnvelope("""{"deviceId":"d","status":"pending"}"""))

            clientFor(transport).register(ENTRY, HARDWARE_ID, KEY_ID, null, null, null)

            // `explicitNulls = false` drops them rather than sending `null`. The three are optional server-side,
            // and a JSON null would be a value where absence was meant.
            assertEquals(setOf("entry", "hardwareId", "keyId", "platform"), transport.bodyJson().keys)
        }

    @Test
    fun `register reports a pending device whatever case the status arrives in`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(successEnvelope("""{"deviceId":"$DEVICE_ID","status":"PENDING"}"""))

            val response = clientFor(transport).register(ENTRY, HARDWARE_ID, KEY_ID, null, null, null)

            assertEquals(DEVICE_ID, response.deviceId)
            // The value is a bare literal in the server's response rather than a serialized enum, so nothing on
            // either side pins its case. The sibling client lowercases before comparing for the same reason.
            assertTrue(response.isPending)
        }

    @Test
    fun `register reports a device that is not pending, and one whose status is absent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val active = FakeDeviceTransport.answering(successEnvelope("""{"deviceId":"d","status":"active"}"""))
            val silent = FakeDeviceTransport.answering(successEnvelope("""{"deviceId":"d"}"""))

            assertFalse(clientFor(active).register(ENTRY, HARDWARE_ID, KEY_ID, null, null, null).isPending)
            // Absent rather than null-valued, which `explicitNulls = false` decodes to null for a nullable
            // property with no default. Not pending, and not an error: the field is optional.
            val quiet = clientFor(silent).register(ENTRY, HARDWARE_ID, KEY_ID, null, null, null)
            assertNull(quiet.status)
            assertFalse(quiet.isPending)
        }

    @Test
    fun `attest sends all eight fields, and the platform key is physically present`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(successEnvelope("""{"registered":true,"isSandbox":false}"""))

            clientFor(transport).attest(
                entry = ENTRY,
                challengeId = CHALLENGE_ID,
                identity = identity(),
                appId = APP_ID,
                token = AttestationToken(TOKEN),
            )

            val body = transport.bodyJson()
            // `platform` is JsonRequired on this route: absent, the server's deserializer throws before any of
            // its own validation runs. It has no Kotlin default for that reason, since `encodeDefaults = false`
            // would drop a defaulted property from the body.
            assertEquals(
                setOf("entry", "challengeId", "deviceId", "keyId", "appId", "attestation", "publicKey", "platform"),
                body.keys,
            )
            assertEquals("Android", body.text("platform"))
            assertEquals(ENTRY, body.text("entry"))
            assertEquals(CHALLENGE_ID, body.text("challengeId"))
            assertEquals(DEVICE_ID, body.text("deviceId"))
            assertEquals(KEY_ID, body.text("keyId"))
            assertEquals(APP_ID, body.text("appId"))
            // The encoded form, not the token: the client owns the encoding so a caller cannot send the
            // raw token, which the service refuses only after it has consumed the challenge.
            assertEquals(ENCODED_TOKEN, body.text("attestation"))
            assertNotEquals(TOKEN, body.text("attestation"))
            assertEquals(PUBLIC_KEY, body.text("publicKey"))
        }

    @Test
    fun `attest and activate accept a success that carries no payload`() =
        runTest(timeout = TEST_TIMEOUT) {
            val attesting = FakeDeviceTransport.answering("""{"responseText":"Success","isSuccess":true}""")
            val activating = FakeDeviceTransport.answering("""{"responseText":"Success","isSuccess":true}""")

            // The shipping sibling client discards both of these bodies, so a service answering with nothing
            // but `isSuccess: true` is a shape a client has already accepted in production. Reaching the
            // response at all is the success signal; there is nothing in it to act on.
            val attested =
                clientFor(attesting).attest(ENTRY, CHALLENGE_ID, identity(), APP_ID, AttestationToken(TOKEN))
            val activated = clientFor(activating).activate(ENTRY, DEVICE_ID, "123456", assertion())

            assertNull(attested.registered)
            assertNull(attested.isSandbox)
            assertNull(activated.status)
        }

    @Test
    fun `activate carries the assertion headers alongside the json content type`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(successEnvelope("""{"deviceId":"d","status":"active"}"""))

            clientFor(transport).activate(ENTRY, DEVICE_ID, "123456", assertion())

            assertEquals(
                mapOf(
                    "X-App-Assertion" to "assertion-value",
                    "X-App-KeyId" to "assertion-key-id",
                    "X-Device-Id" to "assertion-device-id",
                    "X-Assertion-Timestamp" to "2026-08-04T12:00:00.000+0000",
                    PayabliRequest.CONTENT_TYPE_HEADER to PayabliRequest.APPLICATION_JSON,
                ),
                transport.request.headers,
            )
            val body = transport.bodyJson()
            assertEquals(setOf("entry", "deviceId", "activationCode"), body.keys)
            // Values, not just the key set: all three are strings, so a transposed pair would keep the
            // set identical and the suite green. The distinct self-naming constants only guard anything
            // if each one is actually asserted against the key it belongs to.
            assertEquals(ENTRY, body.text("entry"))
            assertEquals(DEVICE_ID, body.text("deviceId"))
            assertEquals("123456", body.text("activationCode"))
        }

    @Test
    fun `the production logger is the default, and constructing with it works`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(
                    successEnvelope("""{"challengeId":"c-1","challenge":"Y2g="}"""),
                )

            // Every other test here injects a recorder, which leaves the default argument — the one every
            // production call site takes — never executed. An uncovered default is one nobody has run.
            val response = DeviceServiceClient(transport).challenge(ENTRY)

            assertEquals("c-1", response.challengeId)
        }

    @Test
    fun `a successful call is logged with the route and nothing from the body`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(
                    successEnvelope("""{"challengeId":"c-1","challenge":"c2VjcmV0LW1hdGVyaWFs"}"""),
                )

            clientFor(transport).challenge(ENTRY)

            val record = logger.records.single()
            // An exact set: only names already on `:core`'s allowlist, so this package needs no widening of
            // that file. `reason` is absent from every record here because the service echoes request data
            // into some of its messages.
            assertEquals(listOf("event", "route", "statusCode"), record.fieldNames)
            assertFalse(record.message.contains("c2VjcmV0LW1hdGVyaWFs"))
        }
}
