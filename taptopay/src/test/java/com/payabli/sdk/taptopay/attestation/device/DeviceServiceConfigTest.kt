package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val ENTRY = "entry-point-value"

private val ASSERTION =
    DeviceAssertion(
        assertion = "assertion-value",
        keyId = "key-id-value",
        deviceId = "device-id-value",
        timestamp = "2026-08-07T12:00:00.000Z",
    )

private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

/**
 * `/config` as a request and a response, without a session around it.
 *
 * It is the one route in this family that is a GET, the one whose path is not its own template, and the one
 * whose refusal can arrive either inside a 200 or as a real status. Each of those is a separate assertion
 * here, because each has its own way of going wrong.
 */
class DeviceServiceConfigTest {
    private val logger = RecordingSdkLogger()

    private fun clientFor(
        body: String,
        statusCode: Int = 200,
    ): Pair<DeviceServiceClient, FakeDeviceTransport> {
        val transport = FakeDeviceTransport.answering(body, statusCode)
        return DeviceServiceClient(transport, logger) to transport
    }

    @Test
    fun `config is a GET carrying no body and the four assertion headers`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, transport) = clientFor(configBody())

            client.config(ENTRY, ASSERTION)

            val request = transport.request
            assertEquals(HttpMethod.GET, request.method)
            assertNull("a GET carries no body", request.body)
            assertEquals(
                "the resolved path names the paypoint",
                "/api/v2/device/taptopay/config/$ENTRY",
                request.path,
            )
            assertEquals(
                "the loggable form is the template, never the resolved path",
                DeviceServiceClient.ROUTE_CONFIG,
                request.route,
            )
            assertEquals(
                mapOf(
                    "X-App-Assertion" to "assertion-value",
                    "X-App-KeyId" to "key-id-value",
                    "X-Device-Id" to "device-id-value",
                    "X-Assertion-Timestamp" to "2026-08-07T12:00:00.000Z",
                ),
                request.headers,
            )
            assertTrue("this route pins the credential it was sent", request.isCredentialPinned)
        }

    @Test
    fun `an active device is given every credential the reader needs`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, _) = clientFor(configBody())

            val credentials = client.config(ENTRY, ASSERTION).credentials

            assertEquals("android", credentials.platform)
            assertEquals("secret-key-value", credentials.secretKey)
            assertEquals("api-key-value", credentials.apiKey)
            assertEquals("merchant-id-value", credentials.merchantId)
            assertEquals("sandbox", credentials.environment)
            assertEquals("USD", credentials.currencyCode)
            assertEquals("merchant-name-value", credentials.merchantName)
            assertEquals("5999", credentials.merchantCategoryCode)
            assertEquals("terminal-id-value", credentials.terminalId)
            assertEquals("pp-id-value", credentials.ppId)
            assertEquals("host-port-value", credentials.hostPort)
        }

    @Test
    fun `printing the credentials names no value`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, transport) = clientFor(configBody())

            val credentials = client.config(ENTRY, ASSERTION).credentials

            // This is the assertion that matters: `toString` is what reaches an exception message, which no
            // logger can redact.
            assertEquals("ReaderCredentials(platform=android)", credentials.toString())
            for (secret in listOf("secret-key-value", "api-key-value", "terminal-id-value", ENTRY)) {
                assertTrue(
                    "$secret reached a log message",
                    logger.records.none { it.message.contains(secret) },
                )
            }
            // The paypoint is in the path and must not be in what the transport is told to record.
            assertTrue(transport.request.route?.contains(ENTRY) != true)
        }

    @Test
    fun `a device the service does not hold as active is forbidden`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, _) = clientFor(declineEnvelope(403, "Device is not active."))

            val failure = failureOf { client.config(ENTRY, ASSERTION) }

            assertTrue("$failure", failure is DeviceServiceException.Forbidden)
            assertEquals(403, (failure as DeviceServiceException).resultCode)
        }

    @Test
    fun `a real 403 is forbidden too, though it never reaches the envelope`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The gateway refuses before any controller runs, so this body carries no envelope at all.
            val (client, _) = clientFor("", statusCode = HTTP_FORBIDDEN)

            val failure = failureOf { client.config(ENTRY, ASSERTION) }

            assertTrue("$failure", failure is DeviceServiceException.Forbidden)
            assertEquals(HTTP_FORBIDDEN, (failure as DeviceServiceException).resultCode)
            assertEquals("the gateway sends no service text", "", failure.reason)
        }

    @Test
    fun `an unusable entry point is told apart from a device that owes a code`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The two arrive under one result code on this route and carry opposite remedies, so the session
            // reads them from the wording or gets one of them wrong.
            val (refused, _) = clientFor(declineEnvelope(403, EntryPointFailures.ENTRY_POINT_UNUSABLE))
            val entryPoint = failureOf { refused.config(ENTRY, ASSERTION, failureMapper = EntryPointFailures) }
            assertTrue("$entryPoint", entryPoint is DeviceServiceException.EntryPointUnusable)

            val (inactive, _) = clientFor(declineEnvelope(403, "Device is not active."))
            val pending = failureOf { inactive.config(ENTRY, ASSERTION, failureMapper = EntryPointFailures) }
            assertTrue("$pending", pending is DeviceServiceException.Forbidden)
        }

    @Test
    fun `the gateway's own 403 stays forbidden even under the entry-point mapper`() =
        runTest(timeout = TEST_TIMEOUT) {
            // It carries no service text, so there is nothing for a wording match to read, and the mapper
            // never sees it: the status override runs on the transport and the mapper on the envelope.
            val (client, _) = clientFor("", statusCode = HTTP_FORBIDDEN)

            val failure = failureOf { client.config(ENTRY, ASSERTION, failureMapper = EntryPointFailures) }

            assertTrue("$failure", failure is DeviceServiceException.Forbidden)
            assertEquals("", (failure as DeviceServiceException).reason)
        }

    @Test
    fun `a credential rotation between attesting and fetching is reported as unattested`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, _) = clientFor(declineEnvelope(401, "Device not attested or attestation revoked."))

            val failure = failureOf { client.config(ENTRY, ASSERTION) }

            assertTrue("$failure", failure is DeviceServiceException.NotAttested)
        }

    @Test
    fun `a success carrying no credentials is undecodable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, _) = clientFor(successEnvelope("{}"))

            val failure = failureOf { client.config(ENTRY, ASSERTION) }

            assertTrue("$failure", failure is DeviceServiceException.Undecodable)
        }

    @Test
    fun `the sibling platform's credentials do not decode as this platform's`() =
        runTest(timeout = TEST_TIMEOUT) {
            // No ppId and no hostPort, which is what makes the platform discriminator self-enforcing.
            val (client, _) =
                clientFor(
                    successEnvelope(
                        """{"credentials":{"platform":"ios","secretKey":"s","apiKey":"a","merchantId":"m",""" +
                            """"environment":"sandbox","currencyCode":"USD","merchantName":"n",""" +
                            """"merchantCategoryCode":"5999","terminalId":"t","appleTtpMerchantId":"",""" +
                            """"terminalProfileId":"p"}}""",
                    ),
                )

            val failure = failureOf { client.config(ENTRY, ASSERTION) }

            assertTrue("$failure", failure is DeviceServiceException.Undecodable)
        }

    @Test
    fun `an entry that is not one path segment is refused before anything is sent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, transport) = clientFor(configBody())

            for (unusable in listOf("", "  ", "a/b", "a?b", "a#b", "a b")) {
                val failure = failureOf { client.config(unusable, ASSERTION) }
                assertTrue("$unusable was accepted", failure is IllegalArgumentException)
            }
            assertEquals(emptyList<Any>(), transport.requests)
        }

    @Test
    fun `a request with no bearer at all still reaches the shared status table`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (client, _) = clientFor("", statusCode = 401)

            val failure = failureOf { client.config(ENTRY, ASSERTION) }

            assertTrue("$failure", failure is PayabliException)
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (failure as PayabliException).code)
        }
}
