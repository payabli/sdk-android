package com.payabli.sdk.taptopay.attestation.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.impl.IntegrityFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * The real Play Integrity library, reached from an emulator.
 *
 * **A token is obtainable from this test APK, and that was measured rather than assumed.** Against a real
 * cloud project with the API enabled, both request shapes returned a token on a `google_apis_playstore`
 * emulator at API 37 and on three physical phones from two manufacturers, spanning API 33 to 36.
 * Debug-signed and installed by adb, all four. `UNRECOGNIZED_VERSION` and `UNLICENSED` are verdict
 * *values* carried inside the token, not reasons the call fails, and reading them needs a server-side
 * decode through the same cloud project. So the App-not-on-Play facts are real and they constrain what the
 * verdict can *say*; they do not stop a token being issued.
 *
 * That is why there is no manual tier: hardware and a Play-Store emulator answered identically at every
 * client-observable level, so a phone can ask nothing this cannot.
 *
 * **This test uses a deliberately invalid project number** and therefore asserts the failure path: that the
 * wiring reaches the library, that the `Task` bridge resumes rather than hanging, and that a platform
 * failure arrives as a mapped [AttestationException] rather than as a Play services exception nobody above
 * this layer can classify. The unit suite covers which code maps where; this covers that the mapping is
 * connected to the platform. Using a real project number here would make the test depend on an external
 * project's configuration, and asserting a token needs that number supplied at the call site.
 *
 * **The specific code is not pinned.** With an invalid project number all four devices answer
 * `CLOUD_PROJECT_NUMBER_IS_INVALID` (-16), but that agreement is shallow: the request is refused on the
 * project number before the device is evaluated at all. An image with no Play Store cannot get that far and
 * can legitimately answer something else, so pinning one image's code would fail on another for no defect.
 * What every image must agree on is that the failure is *mapped*.
 *
 * **One question per test.** The two request shapes are separate integrations with separate error sets,
 * and a single test covering both would report either one's breakage as the same failure.
 */
@RunWith(AndroidJUnit4::class)
class PlayIntegrityAttestorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A cloud project number that is real in shape and not ours.
     *
     * The request never gets far enough for it to matter on an emulator with no Play Store, and using a
     * genuine one would make this test depend on an external project's configuration.
     */
    private val cloudProjectNumber = 1L

    @Test
    fun aStandardRequestFailsAsAMappedError() =
        runTest(timeout = TIMEOUT) {
            val attestor = AttestorFactory.standard(context, cloudProjectNumber)

            val outcome =
                runCatching {
                    attestor.attest(AttestationChallenge.standard("aW5zdHJ1bWVudGVkLXJlcXVlc3QtaGFzaA"))
                }.exceptionOrNull()

            assertTrue(
                "expected a mapped AttestationException, got ${outcome?.let { it::class.java.name } ?: "a token"}",
                outcome is AttestationException,
            )
        }

    @Test
    fun aClassicRequestFailsAsAMappedError() =
        runTest(timeout = TIMEOUT) {
            val attestor = AttestorFactory.classic(context, cloudProjectNumber)

            val outcome =
                runCatching {
                    attestor.attest(AttestationChallenge.classic("aW5zdHJ1bWVudGVkLW5vbmNlLXZhbHVl"))
                }.exceptionOrNull()

            assertTrue(
                "expected a mapped AttestationException, got ${outcome?.let { it::class.java.name } ?: "a token"}",
                outcome is AttestationException,
            )
        }

    /**
     * The failure arrives without an `Activity`, a main looper turn or a background thread of our own.
     *
     * The bridge from the platform's `Task` is the one piece of this integration with no JVM coverage, and
     * its failure mode is a call that never resumes rather than one that throws. `runTest`'s timeout is
     * what asserts it: an unresumed continuation fails here as a timeout instead of hanging the suite.
     */
    @Test
    fun theTaskBridgeResumesWithoutAnActivityOrAWorkerThread() =
        runTest(timeout = TIMEOUT) {
            // The gateway directly, not through an attestor, and that is the whole point of this test.
            // An attestor bounds every platform call at thirty seconds and reports expiry as a mapped
            // failure, so a bridge that never resumed would satisfy an assertion made through it, inside
            // this test's own timeout. Going straight at the gateway removes that cushion: if the
            // continuation is never resumed, nothing completes and runTest fails on time rather than on
            // a result.
            val gateway = PlayClassicIntegrityGateway(context)

            val outcome =
                runCatching {
                    gateway.requestToken("YW5vdGhlci1ub25jZS12YWx1ZQ", cloudProjectNumber)
                }.exceptionOrNull()

            // An IntegrityFailure means the bridge resumed and the error code was extracted, which is the
            // seam's whole job. Mapping that code to a disposition is the unit suite's business.
            assertTrue(
                "expected the bridge to resume with an IntegrityFailure, got ${outcome?.let { it::class.java.name }}",
                outcome is IntegrityFailure,
            )
        }

    private companion object {
        /**
         * Generous, because this makes a real service call and the assertion is about resuming at all.
         * The platform's own guidance for a classic request is that it takes seconds.
         */
        val TIMEOUT = 60.seconds
    }
}
