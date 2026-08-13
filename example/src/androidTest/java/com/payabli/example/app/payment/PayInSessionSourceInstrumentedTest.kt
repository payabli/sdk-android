package com.payabli.example.app.payment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.net.TokenServerClient
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
import org.junit.Assert.assertEquals
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
    fun cACancelledInstallLeavesTheConfigurationRemembered() {
        // The window the other two do not reach: `initialize` installs the session before it returns, so a
        // cancellation arriving after that leaves one installed process-wide. What decides whether the app
        // recovers is whether this source still knows which configuration that was.
        //
        // Read off the configuration handed to the install rather than off the SDK's answer, so this needs no
        // real session installed and leaves none behind for whatever runs next.
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
                        Result.failure(IllegalStateException("not installed, and not needed here"))
                    }
                }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job = scope.launch { source.session() }
            installing.await()
            job.cancelAndJoin()

            holdOpen = false
            source.session()

            // At least two: the cancelled one, and the retry that follows it. A third is allowed and is what
            // happened here — this substitute refuses the retry, so the source falls through and mints a fresh
            // token, which is the recovery path for a configuration that genuinely cannot start.
            assertTrue("the second call never reached the install: $attempted", attempted.size >= 2)
            // The assertion that matters. A fresh token is a different configuration, which the SDK refuses
            // while the session the cancelled attempt installed is still healthy, so the retry has to carry the
            // token the cancelled attempt used.
            assertEquals(
                "a cancelled attempt made the next one mint a new token instead of retrying its own",
                attempted[0].accessToken,
                attempted[1].accessToken,
            )
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
                    environment = DemoEnvironment.SANDBOX,
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
                val body = """{"accessToken":"$TEST_TOKEN-${minted.incrementAndGet()}"}"""
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
        const val TEST_ENTRY_POINT = "instrumented-entry"
        const val TEST_TOKEN = "instrumented-token"
        const val CANCEL_AFTER_MILLIS = 150L
        const val ANSWER_AFTER_MILLIS = 700L
    }
}
