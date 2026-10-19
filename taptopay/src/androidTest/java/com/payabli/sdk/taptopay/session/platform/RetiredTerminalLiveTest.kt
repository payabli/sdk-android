package com.payabli.sdk.taptopay.session.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.PayabliTTP
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 120.seconds

/**
 * A terminal from [PayabliTTP.create] refuses setup once its session is replaced, against the real service.
 * Run this class alone: the session keeps the token provider of whichever class installs it first.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class RetiredTerminalLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Mints real tokens until [refusing] is set, then fresh ones the service refuses, each different. */
    private class SwitchableProvider {
        @Volatile
        var refusing = false
        var calls = 0
            private set

        fun next(): String {
            calls++
            val token = LiveRunSettings.accessToken()
            return if (refusing) altered(token, variant = calls) else token
        }
    }

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("a terminal is built for wired handsets")

    @Test
    fun aTerminalWhoseSessionWasReplacedRefusesSetupAndLandsSessionEnded() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val provider = SwitchableProvider()
                val replaced = initialize(provider)
                val terminal = PayabliTTP.create(replaced, context)
                val seen = mutableListOf<TapToPaySessionState>()
                val collecting = launch { terminal.sessionState.collect { seen += it } }

                provider.refusing = true
                runCatching { replaced.transport.execute(surchargeLookup()) }
                assertTrue(
                    "no token reached this class's provider: another class installed the session first",
                    provider.calls > 0,
                )
                assertEquals(
                    "two refused tokens should leave the session needing re-initialization",
                    SdkState.ReinitializeRequired,
                    PayabliSession.state.value,
                )
                assertTrue("a session needing re-initialization is still the installed one", replaced.isLive)

                provider.refusing = false
                val current = initialize(provider)
                assertNotSame("initialize after a finished session builds a new one", replaced, current)
                assertFalse(replaced.isLive)

                val refusal = runCatching { terminal.initialize() }.exceptionOrNull()
                collecting.cancel()

                assertTrue(refusal.toString(), refusal is TapToPayException)
                assertEquals(PayabliErrorType.SESSION_NOT_INITIALIZED, (refusal as TapToPayException).type)
                assertEquals(
                    TapToPaySessionState.Failed(TapToPayFailureReason.SESSION_ENDED),
                    terminal.sessionState.value,
                )
                assertFalse(
                    "the old terminal moved toward the reader: $seen",
                    seen.any {
                        it == TapToPaySessionState.FetchingConfig ||
                            it == TapToPaySessionState.InitializingReader
                    },
                )
            }
        }

    private suspend fun initialize(provider: SwitchableProvider): PayabliSession =
        PayabliSession.initialize(
            PayabliConfig(
                entryPoint = LiveRunSettings.entry,
                environment = LiveRunSettings.environment,
                tokenProvider = { provider.next() },
            ),
            HostBindings(context),
        )

    /** A read-only route, so the refused token costs nothing but the session it finishes. */
    private fun surchargeLookup() =
        PayabliRequest(
            method = HttpMethod.GET,
            path = "/api/v2/MoneyIn/surcharge/information",
            route = "/api/v2/MoneyIn/surcharge/information",
            query = listOf("entry" to LiveRunSettings.entry),
        )
}
