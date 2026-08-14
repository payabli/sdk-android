package com.payabli.example.app.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.InstrumentedSession
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.TokenHostSource
import com.payabli.example.app.demo.config.TokenServerTarget
import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What a cancelled startup does to the session source.
 *
 * Instrumented rather than a unit test, and the reason is the `Context`: `PayabliSession.initialize` is public
 * and callable from here, but it takes `HostBindings(appContext)` and derives the log level from the
 * application's debuggable flag, neither of which exists on the JVM.
 *
 * **A session installs process-wide and `reset` is internal to `:core`**, so these run in name order and the
 * one that installs nothing goes first. A test that installed a session would leave the next one asking for a
 * different configuration, which the SDK refuses by design.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PayInSessionSourceInstrumentedTest {
    private lateinit var server: SlowTokenServer

    @Before
    fun setUp() {
        server =
            SlowTokenServer().apply {
                answerImmediately = true
                start()
            }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun aCancelledStartupUnwindsInsteadOfAnsweringWithAFailure() =
        runBlocking {
            // `runCatching` here caught the cancellation and turned it into `Result.failure`, so a screen that
            // simply went away reported a startup error. What proves it is that `session()` answered at all:
            // a cancelled call has nothing to answer with.
            //
            // Cancelled inside the install, not during the token request. The request is where an earlier
            // version of this test cancelled, and it passed against the swallow it was written to catch: only
            // the install was ever inside the `runCatching`.
            val installing = CompletableDeferred<Unit>()
            val source =
                sourceAgainst(server) {
                    installing.complete(Unit)
                    awaitCancellation()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var answered: Result<Any>? = null

            val job = scope.launch { answered = source.session().map { it as Any } }
            installing.await()
            job.cancelAndJoin()

            assertTrue("the job finished without being cancelled", job.isCancelled)
            assertNull("session() answered a caller that had gone away: $answered", answered)
        }

    @Test
    fun bTheSourceStillStartsASessionAfterOneWasCancelled() {
        // The cancelled attempt must not leave the source unable to try again, and this one goes through the
        // real install so the answer comes from the SDK rather than from a substitute.
        runBlocking {
            val installing = CompletableDeferred<Unit>()
            var holdOpen = true
            val source =
                sourceAgainst(server) { config ->
                    if (holdOpen) {
                        installing.complete(Unit)
                        awaitCancellation()
                    } else {
                        PayabliSession.initialize(config, HostBindings(context()))
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job = scope.launch { source.session() }
            installing.await()
            job.cancelAndJoin()

            holdOpen = false
            val second = source.session()

            assertTrue("the source was wedged by the cancelled attempt: $second", second.isSuccess)
        }
    }

    @Test
    fun cAFreshTokenReachesTheSameInstalledSession() {
        // A cancelled install leaves a session installed process-wide and this source holding no record of
        // which configuration it was. The next attempt mints a fresh token, and a token is not part of the
        // identity the SDK compares, so it reaches that same session rather than being refused as a second one.
        runBlocking {
            val attempted = CopyOnWriteArrayList<PayabliConfig>()
            val installing = CompletableDeferred<Unit>()
            var holdOpen = true
            val source =
                sourceAgainst(server) { config ->
                    attempted += config
                    if (holdOpen) {
                        installing.complete(Unit)
                        awaitCancellation()
                    } else {
                        PayabliSession.initialize(config, HostBindings(context()))
                    }
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job = scope.launch { source.session() }
            installing.await()
            job.cancelAndJoin()

            holdOpen = false
            val second = source.session()

            assertTrue("the second attempt was refused: $second", second.isSuccess)
            assertNotEquals(
                "the test server handed out the same token twice, so this proves nothing",
                attempted[0].accessToken,
                attempted[1].accessToken,
            )
        }
    }

    @Test
    fun dATokenTheSdkRefusesIsAnsweredRatherThanThrown() {
        // PayabliConfig validates what the token server returned, and a token carrying a newline cannot go in
        // an HTTP header. Built outside the try, that threw out of session(), past the startup's fold, and out
        // of the screen's own coroutine, leaving the step reading "Checking..." with its retry unreachable.
        //
        // Two characters on the wire, backslash and n, which is what JSON decodes into a newline.
        server.token = "abc\\nvalue"
        runBlocking {
            val source = sourceAgainst(server) { error("the install must not be reached") }

            val answered = runCatching { source.session() }

            assertTrue("session() threw instead of answering: $answered", answered.isSuccess)
            assertTrue("a token the SDK refuses was reported as a success", answered.getOrThrow().isFailure)
        }
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun sourceAgainst(
        server: SlowTokenServer,
        startSession: suspend (PayabliConfig) -> Result<PayabliSession>,
    ): PayInSessionSource {
        val target = TokenServerTarget("http://127.0.0.1:${server.port}", TokenHostSource.BuildSetting)
        return PayInSessionSource(
            appContext = context(),
            tokenClient = { TokenServerClient(target) },
            configuration =
                DemoConfiguration(
                    entryPoint = TEST_ENTRY_POINT,
                    appId = "",
                    signingCertificate = "",
                    environment = InstrumentedSession.ENVIRONMENT,
                    diagnosticsEnabled = false,
                ),
            startSession = startSession,
        )
    }

    /**
     * A token server that answers slowly, so a cancellation lands while the request is in flight.
     *
     * The delay is bounded rather than indefinite: the read is blocking, so `cancelAndJoin` waits for it
     * either way, and an indefinite hold would wait for the client's five-second timeout instead.
     */
    private class SlowTokenServer {
        private val socket = ServerSocket(0)
        private val clients = mutableListOf<Socket>()

        @Volatile
        var answerImmediately: Boolean = false

        /** The token to answer with, for a body the SDK refuses. Null mints a fresh usable one per request. */
        @Volatile
        var token: String? = null

        /** A distinct token per request, so a second mint shows up in what reaches the install. */
        private val minted = AtomicInteger(0)

        val port: Int get() = socket.localPort

        fun start() {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: return@thread
                    synchronized(clients) { clients += client }
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            runCatching {
                client.getInputStream().read(ByteArray(1024))
                if (!answerImmediately) Thread.sleep(ANSWER_AFTER_MILLIS)
                val minting = token ?: "$TEST_TOKEN-${minted.incrementAndGet()}"
                val body = """{"accessToken":"$minting"}"""
                // `Connection: close`, and the socket closed after it. Kept alive, the connection abandoned by
                // the cancelled request goes back to `HttpURLConnection`'s pool, and the next call reads the
                // late answer to the request nobody is waiting for any more.
                client.getOutputStream().write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
                    ).toByteArray(),
                )
                client.getOutputStream().flush()
                client.close()
            }
        }

        fun close() {
            runCatching { socket.close() }
            synchronized(clients) { clients.forEach { runCatching { it.close() } } }
        }
    }

    private companion object {
        /** Shared, because a second value here fails whichever class installs its session second. */
        const val TEST_ENTRY_POINT = InstrumentedSession.ENTRY_POINT
        const val TEST_TOKEN = "instrumented-token"
        const val CANCEL_AFTER_MILLIS = 150L
        const val ANSWER_AFTER_MILLIS = 700L
    }
}
