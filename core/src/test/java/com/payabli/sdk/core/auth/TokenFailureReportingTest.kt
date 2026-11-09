package com.payabli.sdk.core.auth

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds
private const val SENTINEL = "sentinel 4111 1111 1111 1111"

/** What a provider call that gave no usable token reports: once, with the reason, and the type it threw. */
class TokenFailureReportingTest {
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun install() = TelemetryRecorders.install { event, properties -> recorded += event to properties }

    @After
    fun clear() = TelemetryRecorders.clear()

    private fun auth(
        providerTimeoutMillis: Long = 30_000,
        tokenProvider: PayabliTokenProvider,
    ) = PayabliAuth(
        PayabliConfig(entryPoint = "entry", environment = PayabliEnvironment.SANDBOX, tokenProvider = tokenProvider),
        DefaultSdkLogger(LogCategory.AUTH, RecordingLogSink()),
        providerTimeoutMillis = providerTimeoutMillis,
    )

    private fun failures() = recorded.filter { it.first == TelemetryEvents.AUTH_TOKEN_FAILED }.map { it.second }

    private fun onlyFailure() = failures().single()

    @Test
    fun `a provider that threw is reported failed, naming the type it threw and never its message`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth { throw SocketTimeoutException(SENTINEL) }

            runCatching { subject.invalidateAndRefresh("initial-token") }

            val properties = onlyFailure()
            assertEquals(TelemetryProperties.Outcome.FAILED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("threw", properties[TelemetryProperty.REASON.key])
            assertEquals("SocketTimeoutException", properties[TelemetryProperty.FAILURE_TYPE.key])
            assertNotNull(properties[TelemetryProperty.DURATION_MS.key])
            assertFalse(properties.toString(), properties.values.any { it.contains(SENTINEL) })
        }

    @Test
    fun `a provider raising cancellation of its own threw`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth { throw CancellationException(SENTINEL) }

            runCatching { subject.invalidateAndRefresh("initial-token") }

            val properties = onlyFailure()
            assertEquals("threw", properties[TelemetryProperty.REASON.key])
            assertEquals("CancellationException", properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    @Test
    fun `a provider that did not answer in time timed out, with no type to name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth(providerTimeoutMillis = 50) { CompletableDeferred<String>().await() }

            runCatching { subject.invalidateAndRefresh("initial-token") }

            val properties = onlyFailure()
            assertEquals(TelemetryProperties.Outcome.FAILED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("timedOut", properties[TelemetryProperty.REASON.key])
            assertNull(properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    @Test
    fun `a token refused before it is installed names which refusal it was`() =
        runTest(timeout = TEST_TIMEOUT) {
            mapOf(
                " " to "blankToken",
                "token\r\nX-Injected: 1" to "unusableToken",
                "initial-token" to "unchangedToken",
            ).forEach { (token, reason) ->
                recorded.clear()
                val subject = auth { token }

                runCatching { subject.invalidateAndRefresh("initial-token") }

                val properties = onlyFailure()
                assertEquals(token, reason, properties[TelemetryProperty.REASON.key])
                assertNull(reason, properties[TelemetryProperty.FAILURE_TYPE.key])
            }
        }

    @Test
    fun `a provider that asked for the token it was minting reentered, reported once`() =
        runTest(timeout = TEST_TIMEOUT) {
            lateinit var subject: PayabliAuth
            subject = auth { subject.accessToken() }

            runCatching { subject.accessToken() }

            val properties = onlyFailure()
            assertEquals("reentered", properties[TelemetryProperty.REASON.key])
            assertNull(properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    @Test
    fun `a provider that handled its reentry and then failed otherwise threw, naming what it raised`() =
        runTest(timeout = TEST_TIMEOUT) {
            lateinit var subject: PayabliAuth
            subject =
                auth {
                    runCatching { subject.accessToken() }
                    throw java.net.SocketTimeoutException("the token server did not answer")
                }

            runCatching { subject.accessToken() }

            val properties = onlyFailure()
            assertEquals("threw", properties[TelemetryProperty.REASON.key])
            assertEquals("SocketTimeoutException", properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    @Test
    fun `callers that joined one provider call record one failure between them`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    throw SocketTimeoutException(SENTINEL)
                }

            val callers = List(3) { async { runCatching { subject.invalidateAndRefresh("initial-token") } } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)
            callers.forEach { it.await() }

            assertEquals(1, calls.get())
            assertEquals(1, failures().size)
        }

    @Test
    fun `a refresh its own caller withdrew, or a fatal error ended, records nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val entered = CompletableDeferred<Unit>()
            val withdrawn =
                auth {
                    entered.complete(Unit)
                    CompletableDeferred<String>().await()
                }
            val caller = async { withdrawn.invalidateAndRefresh("initial-token") }
            entered.await()
            caller.cancel()
            runCatching { caller.await() }

            runCatching { auth { throw OutOfMemoryError(SENTINEL) }.invalidateAndRefresh("initial-token") }

            assertTrue(recorded.toString(), failures().isEmpty())
        }

    @Test
    fun `a recorder that raises an Error releases the claim, so the next refresh mints`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fatal = InternalError(SENTINEL)
            TelemetryRecorders.install { _, _ -> throw fatal }
            listOf<suspend () -> String>({ throw SocketTimeoutException(SENTINEL) }, { " " }).forEach { first ->
                val calls = AtomicInteger()
                val subject = auth { if (calls.getAndIncrement() == 0) first() else "fresh-token" }

                assertSame(fatal, runCatching { subject.invalidateAndRefresh("initial-token") }.exceptionOrNull())

                assertEquals("fresh-token", withTimeout(1_000) { subject.invalidateAndRefresh("initial-token") })
            }
        }

    @Test
    fun `a recorder that raises an Exception leaves the token failure the caller is handed`() =
        runTest(timeout = TEST_TIMEOUT) {
            TelemetryRecorders.install { _, _ -> throw IOException(SENTINEL) }
            val subject = auth { throw SocketTimeoutException(SENTINEL) }

            val thrown = runCatching { subject.invalidateAndRefresh("initial-token") }.exceptionOrNull()

            assertEquals(PayabliErrorType.TOKEN_PROVIDER_FAILED, (thrown as PayabliGenericException).type)
        }
}
