package com.payabli.sdk.taptopay.session.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionException
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 180.seconds

/**
 * Against the real service, a refused token on a device route is renewed and the session reaches ready.
 * The session keeps the provider of the first case in a process, so run each case alone, by method, cold first,
 * with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` so the warm case finds the cold one's device.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class TokenRenewalLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Accepts the credentials and comes up. No card is read in this tier. */
    private class StubReader : TapToPayProvider {
        override suspend fun checkEligibility() = Unit

        override suspend fun configure(credentials: ReaderCredentials) = Unit

        override suspend fun prepareReader() = Unit

        override suspend fun startReading(request: CardReadRequest): CardReadResult =
            throw UnsupportedOperationException("no reader is armed in this tier")
    }

    /** Answers a refused token first, then fresh ones, and records the fingerprint of each. */
    private class RefusedFirstProvider {
        val fingerprints = mutableListOf<String>()

        fun next(): String {
            val token = LiveRunSettings.accessToken()
            val answered = if (fingerprints.isEmpty()) altered(token) else token
            fingerprints += fingerprint(answered)
            return answered
        }
    }

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("the session needs a device the service can attest")

    @Test
    fun aColdStartWhoseFirstTokenIsRefusedRenewsItAndComesUp() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                AttestedDeviceStore(DeviceTrust.open(context).store).clear(LiveRunSettings.entry)
                val provider = RefusedFirstProvider()
                val coordinator = coordinator(provider)

                val owed = runCatching { coordinator.initialize() }.exceptionOrNull()
                Log.i(LiveTapToPay.LIVE_TAG, "cold start answered ${owed?.javaClass?.simpleName ?: "ready"}")
                assertRenewedOnce(provider)
                if (owed is TapToPaySessionException.PendingActivation) {
                    val record = AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)
                    assertNotNull("the session asked for activation and recorded nothing to activate", record)
                    coordinator.activateDevice(LiveTapToPay.mintActivationCode(record!!.deviceId))
                    coordinator.initialize()
                } else if (owed != null) {
                    throw owed
                }

                assertEquals(TapToPaySessionState.Ready, coordinator.state.value)
                assertEquals("the renewed token served the rest of the session", 2, provider.fingerprints.size)
            }
        }

    @Test
    fun aWarmStartWhoseFirstTokenIsRefusedRenewsItAndComesUp() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                assertNotNull(
                    "a warm start needs the device the cold case activated, left installed after that run",
                    AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry),
                )
                val provider = RefusedFirstProvider()
                val coordinator = coordinator(provider)

                coordinator.initialize()
                Log.i(LiveTapToPay.LIVE_TAG, "warm start answered ${coordinator.state.value}")

                assertRenewedOnce(provider)
                assertEquals(TapToPaySessionState.Ready, coordinator.state.value)
            }
        }

    private suspend fun coordinator(provider: RefusedFirstProvider): TapToPaySessionCoordinator {
        val session =
            PayabliSession.initialize(
                PayabliConfig(
                    entryPoint = LiveRunSettings.entry,
                    environment = LiveRunSettings.environment,
                    tokenProvider = { provider.next() },
                ),
                HostBindings(context),
            )
        return TapToPaySessionCoordinator(
            entry = LiveRunSettings.entry,
            enrollment = LiveTapToPay.enrollment(context),
            client = DeviceServiceClient(session.transport),
            reader = StubReader(),
            manager = TapToPaySessionManager(),
            renewToken = session::renewRefusedToken,
            isLive = { session.isLive },
        )
    }

    /** The refused token was the session's first, and exactly one fresh token replaced it. */
    private fun assertRenewedOnce(provider: RefusedFirstProvider) {
        Log.i(LiveTapToPay.LIVE_TAG, "tokens asked for: ${provider.fingerprints}")
        assertTrue(
            "no token reached this case's provider: another case installed the session first",
            provider.fingerprints.isNotEmpty(),
        )
        assertEquals("one refused token, then one fresh token", 2, provider.fingerprints.size)
        assertTrue("the fresh token is the refused one", provider.fingerprints[0] != provider.fingerprints[1])
    }
}

/** The token with one character of its signature changed, so the service refuses it. */
private fun altered(token: String): String {
    val signatureStart = token.lastIndexOf('.') + 1
    check(signatureStart > 0 && token.length - signatureStart > 8) { "the token server answered no signed token" }
    val at = signatureStart + (token.length - signatureStart) / 2
    val replacement = if (token[at] == 'A') 'B' else 'A'
    return token.substring(0, at) + replacement + token.substring(at + 1)
}

/** The first 8 hex of the token's SHA-256, which is all a log may carry of it. */
private fun fingerprint(token: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .take(4)
        .joinToString("") { "%02x".format(it) }
