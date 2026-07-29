package com.payabli.sdk.core.auth

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The 2.2 auth holder: refresh de-duplication, the change flow, and how a provider failure surfaces. */
class PayabliAuthTest {
    private val sink = RecordingLogSink()

    private fun auth(tokenProvider: PayabliTokenProvider? = null) =
        PayabliAuth(
            PayabliConfig(
                accessToken = "initial-token",
                entryPoint = "entry",
                environment = PayabliEnvironment.SANDBOX,
                tokenProvider = tokenProvider,
            ),
            DefaultPayabliLogger(LogCategory.AUTH, sink),
        )

    private suspend fun failureFrom(block: suspend () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `the initial token comes from the config`() =
        runTest {
            assertEquals("initial-token", auth().accessToken())
        }

    @Test
    fun `concurrent refreshes invoke the provider exactly once`() =
        runTest {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "fresh-token"
                }

            // Five callers, all arriving while the first provider call is still parked on the gate.
            val waiters = List(5) { async { subject.invalidateAndRefresh() } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)

            assertEquals(List(5) { "fresh-token" }, waiters.map { it.await() })
            assertEquals("exactly one provider invocation", 1, calls.get())
            assertEquals("fresh-token", subject.accessToken())
        }

    @Test
    fun `a later refresh invokes the provider again`() =
        runTest {
            val calls = AtomicInteger()
            val subject = auth { "token-${calls.incrementAndGet()}" }

            assertEquals("token-1", subject.invalidateAndRefresh())
            assertEquals("token-2", subject.invalidateAndRefresh())
            assertEquals(2, calls.get())
        }

    @Test
    fun `the change flow emits once per successful refresh`() =
        runTest {
            val calls = AtomicInteger()
            val subject = auth { "token-${calls.incrementAndGet()}" }
            val seen = mutableListOf<String>()
            val collector = launch { subject.tokenChanges.collect { seen += it } }
            yield()

            subject.invalidateAndRefresh()
            yield()
            subject.invalidateAndRefresh()
            yield()

            assertEquals(listOf("token-1", "token-2"), seen)
            collector.cancel()
        }

    @Test
    fun `de-duplicated waiters see one emission, not one each`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "fresh-token"
                }
            val seen = mutableListOf<String>()
            val collector = launch { subject.tokenChanges.collect { seen += it } }
            yield()

            val waiters = List(3) { async { subject.invalidateAndRefresh() } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)
            waiters.forEach { it.await() }
            yield()

            assertEquals(listOf("fresh-token"), seen)
            collector.cancel()
        }

    @Test
    fun `no provider makes an expired token terminal`() =
        runTest {
            val subject = auth(tokenProvider = null)

            val failure = failureFrom { subject.invalidateAndRefresh() }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertEquals("the token is unchanged", "initial-token", subject.accessToken())
        }

    @Test
    fun `a provider failure surfaces as token expired without its own message`() =
        runTest {
            val sentinel = "SENTINEL-BACKEND-BODY"
            val subject = auth { throw IOException("host backend said: $sentinel") }

            val failure = failureFrom { subject.invalidateAndRefresh() }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertFalse(
                "the provider's message reached the caller",
                failure.stackTraceToString().contains(sentinel),
            )
        }

    @Test
    fun `waiters receive the same failure as the initiator, not the raw provider error`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    throw IOException("provider down")
                }

            val waiters = List(3) { async { runCatching { subject.invalidateAndRefresh() } } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)

            val outcomes = waiters.map { it.await().exceptionOrNull() }
            assertEquals("exactly one provider invocation", 1, calls.get())
            for (outcome in outcomes) {
                assertTrue("got $outcome", outcome is PayabliException)
                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (outcome as PayabliException).code)
            }
        }

    @Test
    fun `a failed refresh does not wedge the next attempt`() =
        runTest {
            val calls = AtomicInteger()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) throw IOException("first attempt fails") else "recovered"
                }

            failureFrom { subject.invalidateAndRefresh() }
            assertEquals("recovered", subject.invalidateAndRefresh())
            assertEquals("recovered", subject.accessToken())
        }

    @Test
    fun `a provider that reads the token does not deadlock`() =
        runTest {
            var holder: PayabliAuth? = null
            val subject = auth { holder!!.accessToken() }
            holder = subject

            // Returns the last known token: a re-entrant caller cannot wait for itself.
            assertEquals("initial-token", withTimeoutOrNull(5_000) { subject.invalidateAndRefresh() })
        }

    @Test
    fun `a provider that refreshes again does not deadlock`() =
        runTest {
            var holder: PayabliAuth? = null
            val subject = auth { holder!!.invalidateAndRefresh() }
            holder = subject

            assertEquals("initial-token", withTimeoutOrNull(5_000) { subject.invalidateAndRefresh() })
        }

    @Test
    fun `a provider that never returns fails on the deadline and frees the claim`() =
        runTest {
            val calls = AtomicInteger()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) CompletableDeferred<String>().await() else "recovered"
                }

            val failure = failureFrom { subject.invalidateAndRefresh() }
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)

            // The claim was released, so the next attempt is not wedged behind the stuck one.
            assertEquals("recovered", subject.invalidateAndRefresh())
        }

    @Test
    fun `a cancelled initiator gives waiters a token failure, not its cancellation`() =
        runTest {
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val subject =
                auth {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    CompletableDeferred<String>().await()
                }

            val initiator = async { subject.invalidateAndRefresh() }
            entered.await()
            val waiter = async { runCatching { subject.accessToken() } }
            yield()
            initiator.cancel()

            val outcome = waiter.await().exceptionOrNull()
            assertTrue("got $outcome", outcome is PayabliException)
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (outcome as PayabliException).code)
            assertEquals("exactly one provider invocation", 1, calls.get())
        }

    @Test
    fun `a provider error carrying another code still surfaces as token expired`() =
        runTest {
            val subject =
                auth {
                    throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, "provider used our own type")
                }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failureFrom { subject.invalidateAndRefresh() }.code)
        }

    @Test
    fun `a read during a refresh returns the fresh token`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "fresh-token"
                }

            val refresh = async { subject.invalidateAndRefresh() }
            while (calls.get() == 0) yield()
            val read = async { subject.accessToken() }
            yield()
            gate.complete(Unit)

            assertEquals("fresh-token", refresh.await())
            assertEquals("the read waited for the rotation", "fresh-token", read.await())
        }

    @Test
    fun `the log records the refresh without the token`() =
        runTest {
            val subject = auth { "SENTINEL-FRESH-TOKEN" }

            subject.invalidateAndRefresh()

            val logged = sink.records.joinToString("\n") { it.message }
            assertTrue(logged.contains("token_refreshed"))
            assertFalse("the token was logged", logged.contains("SENTINEL-FRESH-TOKEN"))
        }
}
