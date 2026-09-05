package com.payabli.sdk.taptopay.enrollment.platform

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.attestation.device.EntryPointFailures
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionFailures
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 120.seconds

/**
 * The refusal for an entry point this caller cannot act on, checked against the service's own wording.
 *
 * The mapper compares one literal, so a live call is the only thing that can say the literal is still the
 * one that comes back. The unit tier supplies the string itself and would pass a service that had reworded
 * it.
 *
 * **Its own class so `build.gradle.kts` can exclude it by class name**, which is how every other live test
 * in this module is excluded. It is off by default, because this module ships ahead of the service change it
 * asserts. Deployments answer this refusal one way before that change and another way after, and there is no
 * third answer, so running it against an environment that has not taken the change is a red that means a
 * date rather than a defect. Asserting whichever answer arrives would make the test unable to fail, which is
 * why it is gated instead of widened.
 *
 * To turn it on, check that the service change is deployed **to the environment about to be used**, not
 * merely merged: a merge to the backend's own branch is not a deployment, and environments take it at
 * different times. Then, with the live tier's usual arguments:
 *
 * ```
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=<name> -Ppayabli.ttp.entryPointRefusalDeployed=true
 * ```
 *
 * An environment beyond `sandbox` and `production` is not in this checkout. Add it to the build first,
 * with `payabli.sdk.extraEnvironments=<name>=https://<host>.payabli.com` in
 * `~/.gradle/gradle.properties`, or `payabli.ttp.environment` names one the SDK does not carry and the
 * run stops at configuration.
 *
 * A red with the property set is worth reading: either the environment does not have the change after all,
 * or the wording moved and the mapper's literal is now wrong, which is the whole reason this test exists.
 * The failure message prints what came back so the two are distinguishable.
 *
 * **Costs nothing, and is the one live test here that is free to repeat.** No challenge is spent, no attempt
 * consumed and no device row left behind, because the call is refused before any of that is reached. The
 * entry is derived from the configured one, so it needs no second paypoint and the token stays scoped to the
 * real one.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class EntryPointRefusalLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() {
        // Fails rather than skips, on the same reasoning as the rest of this tier: this class is only ever
        // invoked by name, so reaching it on an emulator means the run was pointed at the wrong target.
        assertFalse(
            "the live tier is for wired handsets; an emulator's device key is software-backed",
            Build.HARDWARE in EMULATED,
        )
    }

    @Test
    fun theServiceRefusesAnEntryPointWithTheWordingTheMapperExpects() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val client = DeviceServiceClient(session().transport)

                val thrown =
                    runCatching {
                        client.challenge(
                            entry = "${LiveRunSettings.entry}-nx",
                            failureMapper = EntryPointFailures,
                        )
                    }.exceptionOrNull()

                Log.i(LIVE_TAG, "challenge refused with ${thrown?.javaClass?.simpleName}")
                assertTrue(
                    "expected the entry-point classification and got $thrown, which is what this " +
                        "environment answers before the service change lands",
                    thrown is DeviceServiceException.EntryPointUnusable,
                )
                // The landing is the half a host acts on, and the reason this is not only a wording check.
                assertEquals(
                    TapToPaySessionState.Failed(TapToPayFailureReason.CONFIGURATION_REJECTED),
                    TapToPaySessionFailures.landingFor(thrown!!),
                )
            }
        }

    private suspend fun session(): PayabliSession =
        PayabliSession
            .initialize(
                PayabliConfig(
                    entryPoint = LiveRunSettings.entry,
                    environment = LiveRunSettings.environment,
                    tokenProvider = { LiveRunSettings.accessToken() },
                ),
                HostBindings(context),
            ).getOrThrow()

    private companion object {
        val EMULATED = setOf("ranchu", "goldfish")

        /** The tag this tier shares, so a live run's output is one logcat filter. */
        const val LIVE_TAG = "PayabliLiveRun"
    }
}
