package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliRateLimitException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.taptopay.attestation.impl.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val ENTRY = "a-test-entrypoint"

/** Server text that echoes what was sent, which is why `reason` is displayable and never loggable. */
private const val ECHOING_REASON = "Invalid activation code."

/** Placeholders: these tests are about what comes back, so nothing here reaches an assertion on the request. */
private fun probeIdentity() = DeviceIdentity(deviceId = "d", keyId = "k", publicKey = "p")

private fun probeAssertion() =
    DeviceAssertion(assertion = "a", keyId = "k", deviceId = "d", timestamp = "2026-08-04T12:00:00.000+0000")

class DeviceFailureMappingTest {
    private val logger = RecordingSdkLogger()

    private suspend fun challengeAgainst(
        body: String,
        statusCode: Int = 200,
    ): Throwable? =
        runCatching {
            DeviceServiceClient(FakeDeviceTransport.answering(body, statusCode), logger).challenge(ENTRY)
        }.exceptionOrNull()

    @Test
    fun `each result code becomes its own disposition`() =
        runTest(timeout = TEST_TIMEOUT) {
            val cases =
                listOf(
                    400 to DeviceServiceException.BadRequest::class,
                    401 to DeviceServiceException.NotAttested::class,
                    403 to DeviceServiceException.Forbidden::class,
                    404 to DeviceServiceException.NotFound::class,
                    500 to DeviceServiceException.ServerFailure::class,
                )

            for ((code, expected) in cases) {
                val failure = challengeAgainst(declineEnvelope(code, "refused"))

                assertEquals("result code $code", expected, failure!!::class)
                assertEquals(code, (failure as DeviceServiceException).resultCode)
            }
        }

    @Test
    fun `an unforeseen code in the server range is still the server's failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            // `>=` rather than an equality on 500, for the reason PayabliHttpErrors gives about real statuses:
            // narrowing it would file a 503-shaped result under "unrecognised" instead of "the service failed".
            val failure = challengeAgainst(declineEnvelope(503, "unavailable"))

            assertTrue(failure is DeviceServiceException.ServerFailure)
        }

    @Test
    fun `a code this SDK does not know, and a decline carrying none, are reported as unclassified`() =
        runTest(timeout = TEST_TIMEOUT) {
            val unknown = challengeAgainst(declineEnvelope(418, "refused"))
            val codeless = challengeAgainst(declineEnvelope(null, "refused"))

            // Not folded into BadRequest: a future code arriving dressed as a request defect would have a
            // caller acting on a classification nobody made.
            assertTrue(unknown is DeviceServiceException.Unclassified)
            assertEquals(418, (unknown as DeviceServiceException).resultCode)
            assertTrue(codeless is DeviceServiceException.Unclassified)
            assertNull((codeless as DeviceServiceException).resultCode)
        }

    @Test
    fun `the four activation failures share one disposition, and a mapper is what splits them`() =
        runTest(timeout = TEST_TIMEOUT) {
            val unmapped = challengeAgainst(declineEnvelope(400, ECHOING_REASON))

            // The service reports a wrong code, a spent lockout, an expired window and a rejected assertion
            // all as result code 400, distinguishable only by text that is scheduled to become uniform. So the
            // 400 bucket stays whole here, and the text-matching lives wherever accepting that risk is worth it.
            assertTrue(unmapped is DeviceServiceException.BadRequest)
            assertEquals(ECHOING_REASON, (unmapped as DeviceServiceException).reason)
        }

    @Test
    fun `a failure renders its classification and code, never the server's words`() =
        runTest(timeout = TEST_TIMEOUT) {
            val failure = challengeAgainst(declineEnvelope(400, ECHOING_REASON)) as DeviceServiceException

            // toString reaches diagnostics and aggregators the logger cannot redact, so the reason stays out of
            // it while the two facts a reader needs stay in.
            assertEquals("BadRequest(resultCode=400)", failure.toString())
            assertFalse(failure.toString().contains(ECHOING_REASON))
        }

    @Test
    fun `a mapper's failure is raised in place of the default classification`() =
        runTest(timeout = TEST_TIMEOUT) {
            val wrongCode = IllegalStateException("the activation code was wrong")
            val transport = FakeDeviceTransport.answering(declineEnvelope(400, ECHOING_REASON))

            val failure =
                runCatching {
                    DeviceServiceClient(transport, logger).challenge(ENTRY) { _, reason ->
                        if (reason == ECHOING_REASON) wrongCode else null
                    }
                }.exceptionOrNull()

            assertSame(wrongCode, failure)
        }

    @Test
    fun `a mapper that declines to classify defers rather than swallowing the failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(declineEnvelope(401, "revoked"))

            val failure =
                runCatching {
                    // A mapper that cares about one case says nothing about the rest, and null must not read
                    // as "this was fine": a decline the mapper ignores is still a decline.
                    DeviceServiceClient(transport, logger).challenge(ENTRY) { _, _ -> null }
                }.exceptionOrNull()

            assertTrue(failure is DeviceServiceException.NotAttested)
        }

    @Test
    fun `a transport failure arrives as a core exception rather than a device one`() =
        runTest(timeout = TEST_TIMEOUT) {
            // 429 with no envelope at all: a proxy or the gateway, not this service speaking. The two
            // taxonomies are disjoint on purpose, so which one a caller catches says which layer failed.
            val failure = challengeAgainst(body = "", statusCode = 429)

            assertTrue(failure is PayabliRateLimitException)
            assertFalse(failure is DeviceServiceException)
        }

    @Test
    fun `a transport failure is decided by the status and never by the envelope under it`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A success envelope behind a 403. The status wins, because a body that claims success from a
            // response the transport rejected is not evidence of anything.
            val failure =
                challengeAgainst(successEnvelope("""{"challengeId":"c","challenge":"Y2g="}"""), statusCode = 403)

            assertEquals(PayabliErrorCode.PERMISSION_DENIED, (failure as PayabliException).code)
        }

    @Test
    fun `a success whose payload is missing a required field is undecodable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val failure = challengeAgainst(successEnvelope("""{"challenge":"Y2g="}"""))

            // Not a decline: the service said success. Something between the two of us is wrong about the
            // contract, and filing it under the service's fault would lose the cause.
            assertTrue(failure is DeviceServiceException.Undecodable)
            assertNull((failure as DeviceServiceException).resultCode)
        }

    @Test
    fun `a success with no payload at all is undecodable where the fields are needed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val failure = challengeAgainst("""{"responseText":"Success","isSuccess":true}""")

            assertTrue(failure is DeviceServiceException.Undecodable)
        }

    @Test
    fun `a body that is not json at all is undecodable rather than a decline`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A proxy's HTML behind a 200. `declineOutcome` reads it as "not a decline" by design, so the
            // canonical decode error is produced in one place: here.
            val failure = challengeAgainst("<html>an interception page</html>")

            assertTrue(failure is DeviceServiceException.Undecodable)
        }

    @Test
    fun `a decline is logged with its code and never with the server's words`() =
        runTest(timeout = TEST_TIMEOUT) {
            challengeAgainst(declineEnvelope(400, ECHOING_REASON))

            val record = logger.records.single()
            assertEquals(LogLevel.WARN, record.level)
            assertEquals(listOf("event", "route", "errorCode"), record.fieldNames)
            // The reason can echo request data, so it is displayable and never loggable. `statusCode` is
            // absent from this record on purpose: the status was 200 and printing it beside a failure would
            // read as a contradiction.
            assertFalse(record.message.contains(ECHOING_REASON))
        }

    @Test
    fun `the decline QA actually returns is classified and read correctly`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Captured verbatim from api-qa: POST /register for a paypoint with no card gateway. Recorded
            // rather than composed, so this asserts against the service's own output including the keys it
            // sends that this SDK does not model — `responseCode` and `roomId` on the envelope, and the whole
            // ResponseApiData surface under `responseData`, which is typed `object` server-side.
            val recorded =
                """
                {"responseText":"Declined","isSuccess":false,"responseCode":1,"roomId":null,
                 "pageIdentifier":null,
                 "responseData":{"resultCode":400,"resultText":"No card gateway configured for this paypoint.",
                                 "authCode":null,"referenceId":null,"customerId":0,"avsResponseText":null,
                                 "cvvResponseText":null,"methodReferenceId":null,"vendorId":null,
                                 "accountVerificationLogId":null}}
                """.trimIndent()

            val failure = challengeAgainst(recorded) as DeviceServiceException

            assertTrue(failure is DeviceServiceException.BadRequest)
            assertEquals(400, failure.resultCode)
            assertEquals("No card gateway configured for this paypoint.", failure.reason)
        }

    @Test
    fun `the DTO validation 400 QA returns is a validation error, not a device one`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Also captured verbatim: POST /attest with the `platform` key absent. The service's DTO validation
            // refuses it before any controller runs, so there is no envelope in this body at all — a real 400
            // carrying RFC 9457 problem+json. Proof that the family has two failure shapes and that running
            // PayabliHttpErrors before looking for an envelope is what makes the second one legible.
            val recorded =
                """
                {"errors":{"$":["JSON deserialization for type 'AttestRequest' was missing required properties including: 'platform'."],
                           "request":["The request field is required."]},
                 "status":400,"title":"One or more validation errors occurred.",
                 "traceId":"00-269fc2f7f7e5f06869c50c7cd8354f24-a6ae79f202c39512-01",
                 "type":"https://tools.ietf.org/html/rfc9110#section-15.5.1"}
                """.trimIndent()

            val failure = challengeAgainst(recorded, statusCode = 400)

            assertFalse(failure is DeviceServiceException)
            assertEquals(PayabliErrorCode.VALIDATION_ERROR, (failure as PayabliException).code)
            assertEquals("One or more validation errors occurred.", failure.reason)
            // Empty, and it should not be: `errors` names the refused field, and the string above says which
            // property was missing. `:core` decodes that map as `{message, suggestion}` objects while the
            // service sends strings, so the decode fails and the map degrades to empty. Asserted as it stands
            // rather than as it ought to be, so this test says what the SDK does today; PLA-2351 fixes the
            // decode in `:core` and flips this line to the assertion it wants to be.
            assertTrue((failure as PayabliValidationException).fieldErrors.isEmpty())
        }

    @Test
    fun `a 200 that never claims success is undecodable, not a synthetic success`() =
        runTest(timeout = TEST_TIMEOUT) {
            // `{}` is the whole body. It is not a decline, because `declineOutcome` reads an absent `isSuccess`
            // as "not false" and returns null; and it is not a success either, because nothing in it says so.
            // The two routes that tolerate an absent payload are the ones this could hurt: without a positive
            // check they substitute an empty ack and report success for a response the service never sent.
            val attesting = FakeDeviceTransport.answering("{}")
            val activating = FakeDeviceTransport.answering("{}")
            val client = DeviceServiceClient(attesting, logger)

            val attested =
                runCatching {
                    client.attest(ENTRY, "c", probeIdentity(), "com.payabli.example", "a")
                }.exceptionOrNull()
            val activated =
                runCatching {
                    DeviceServiceClient(activating, logger)
                        .activate(ENTRY, "d", "123456", probeAssertion())
                }.exceptionOrNull()

            assertTrue(attested is DeviceServiceException.Undecodable)
            assertTrue(activated is DeviceServiceException.Undecodable)
        }

    @Test
    fun `a 200 whose payload looks right but claims nothing is still undecodable`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A well-formed `responseData` with no `isSuccess` around it. The payload is not the assertion of
            // success; the envelope is. An intermediary that returns a body shaped like the right one must not
            // be able to manufacture an attestation.
            val body = """{"responseText":"Success","responseData":{"challengeId":"c-1","challenge":"Y2g="}}"""

            val failure = challengeAgainst(body)

            assertTrue(failure is DeviceServiceException.Undecodable)
        }

    @Test
    fun `no part of the response body survives anywhere in the cause chain`() =
        runTest(timeout = TEST_TIMEOUT) {
            // kotlinx quotes the input it choked on, and a device body holds a challenge, a challengeId and a
            // deviceId. Redacting this class's own toString buys nothing if the cause underneath it carries the
            // body: printStackTrace and a host app's crash reporter render the whole chain, and that reporter is
            // outside anything this SDK scrubs.
            val secret = "Y2hhbGxlbmdlLW1hdGVyaWFsLXRoYXQtbXVzdC1ub3QtbGVhaw"
            val body = """{"responseText":"Success","isSuccess":true,"responseData":{"challenge":"$secret",}}"""

            val failure = challengeAgainst(body) as DeviceServiceException.Undecodable

            val rendered =
                StringBuilder()
                    .apply {
                        var link: Throwable? = failure
                        while (link != null) {
                            append(link.javaClass.name).append(' ').append(link.message).append('\n')
                            link = link.cause
                        }
                    }.toString()
            assertFalse(rendered.contains(secret))
            // The type and its stack survive, because a class, method, file and line are the diagnostic value
            // and none of them carries a subject. Only the message is dropped.
            assertTrue(rendered.contains("SerializationException") || rendered.contains("JsonDecodingException"))
            assertTrue(failure.cause!!.stackTrace.isNotEmpty())
        }

    @Test
    fun `a call that fails never records a success first`() =
        runTest(timeout = TEST_TIMEOUT) {
            // `/challenge` cannot proceed without its fields, so this body is a failure for it. The success
            // record used to be written before the caller discovered that, leaving a log that said the call
            // succeeded and no failure record anywhere beside it — an incident reading as a success the caller
            // never received. Whether an absent payload is usable is now settled before anything is recorded.
            val failure = challengeAgainst("""{"responseText":"Success","isSuccess":true}""")

            assertTrue(failure is DeviceServiceException.Undecodable)
            val record = logger.records.single()
            assertEquals(LogLevel.WARN, record.level)
            assertFalse(record.message.contains("succeeded"))
        }

    @Test
    fun `a JVM error from the transport reaches the caller unchanged and records nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fatal = OutOfMemoryError("simulated")
            val transport = FakeDeviceTransport { throw fatal }

            val thrown =
                runCatching { DeviceServiceClient(transport, logger).challenge(ENTRY) }.exceptionOrNull()

            assertSame(fatal, thrown)
            assertTrue(logger.records.isEmpty())
        }

    // The matching guarantee one layer in — that neither *decode* converts a JVM Error into `Undecodable` —
    // has no test, and the honest reason is that it has no injection point: `Status.serializer()` and
    // `PayabliJson.format` are both fixed, and deeply nested JSON decoded into a flat target fails fast with a
    // SerializationException rather than recursing into a StackOverflowError. It rests on the catch clauses
    // naming `SerializationException` instead of `Throwable`, which is where a reviewer has to see it. That is
    // how `:core` and `AttestationChallenge.classic` hold the same rule. A sabotage run flags this: reverting
    // either catch to `Throwable` turns nothing red.

    @Test
    fun `nothing is retried, on any outcome`() =
        runTest(timeout = TEST_TIMEOUT) {
            val declining = FakeDeviceTransport.answering(declineEnvelope(500, "unavailable"))
            val failing = FakeDeviceTransport { PayabliResponse(503) }

            runCatching { DeviceServiceClient(declining, logger).challenge(ENTRY) }
            runCatching { DeviceServiceClient(failing, logger).challenge(ENTRY) }

            // Retryable-looking failures both. `/attest` consumes the challenge on read and `/activate` spends
            // one of five attempts, so retrying any single call in this family is unsafe even where it looks
            // free; the duplicate-safe unit is the whole cold sequence, which this client does not own.
            assertEquals(1, declining.requests.size)
            assertEquals(1, failing.requests.size)
        }
}
