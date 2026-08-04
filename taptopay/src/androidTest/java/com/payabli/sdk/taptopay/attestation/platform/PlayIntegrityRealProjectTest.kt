package com.payabli.sdk.taptopay.attestation.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * The one thing the failure-path suite cannot show: that a request against a **real** cloud project
 * actually returns a token from this test APK.
 *
 * It matters because the opposite was believed and written down. The reasoning was that a debug-signed,
 * adb-installed `.test` package can never be Play-recognised or licensed, so no token could be had on any
 * device or image. The first two premises are true and the conclusion does not follow: those are verdict
 * *values* carried inside the token, not reasons the call fails. Measured against a real project, both
 * request shapes returned a token on a `google_apis_playstore` emulator at API 37 and on three physical
 * phones from two manufacturers, spanning API 33 to 36.
 *
 * That measurement is also why no manual hardware tier exists: the emulator and all three phones agreed.
 *
 * **Runs only where the project number is configured.** Set `payabli.cloudProjectNumber` in
 * `~/.gradle/gradle.properties`. Without it the build filters this class out of the run by name, which is
 * neither a skip nor a failure: a permanent skip cannot be told apart from a regression that started
 * skipping, and failing would make red the ordinary outcome of `connectedAndroidTest` for anyone without
 * the property. `taptopay/build.gradle.kts` carries both that reasoning and why the filter names a class
 * rather than an annotation.
 *
 * **Renaming or moving this class silently re-enables it.** The filter is a class name in the build file,
 * so a rename that does not update it puts these back into every run.
 *
 * **A failure reading `Throttled(errorCode=-8)` is rate limiting, not a defect.** Running this class in a
 * tight loop reaches it: it appeared after roughly twenty to thirty requests inside an hour, which is far
 * below the documented daily maximum, so it was short-term limiting rather than the budget running out.
 * It had cleared by the end of a two-minute wait. Re-run before investigating, and note the attestor now
 * holds a suppression window of its own, so an immediate re-run is refused locally without reaching the
 * platform.
 *
 * What this still cannot see is the verdict *contents* — device integrity, licensing, app recognition are
 * inside the token, which is decodable only server-side through the same cloud project. Do not add an
 * assertion about them here; the SDK must not parse the token at all.
 */
@RunWith(AndroidJUnit4::class)
class PlayIntegrityRealProjectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val cloudProjectNumber: Long
        get() {
            val supplied =
                InstrumentationRegistry.getArguments().getString("cloudProjectNumber")
                    ?: error(
                        "set payabli.cloudProjectNumber in ~/.gradle/gradle.properties to a Google Cloud " +
                            "project with the Play Integrity API enabled; it is never committed here",
                    )
            return supplied.toLongOrNull() ?: error("payabli.cloudProjectNumber must be numeric: $supplied")
        }

    @Test
    fun aStandardRequestAgainstARealProjectReturnsAToken() =
        runTest(timeout = TIMEOUT) {
            val attestor = AttestorFactory.standard(context, cloudProjectNumber)

            val token = attestor.attest(AttestationChallenge.standard("cmVhbC1wcm9qZWN0LXJlcXVlc3QtaGFzaA"))

            // Non-empty and nothing else. The token is opaque by contract, so any stronger client-side
            // assertion would be asserting a shape we have promised not to depend on.
            assertTrue("the platform returned an empty token", token.value.isNotEmpty())
        }

    @Test
    fun aClassicRequestAgainstARealProjectReturnsAToken() =
        runTest(timeout = TIMEOUT) {
            val attestor = AttestorFactory.classic(context, cloudProjectNumber)

            val token = attestor.attest(AttestationChallenge.classic("cmVhbC1wcm9qZWN0LW5vbmNlLXZhbA"))

            assertTrue("the platform returned an empty token", token.value.isNotEmpty())
        }

    private companion object {
        /** A classic request makes a real service call and the platform says it takes seconds. */
        val TIMEOUT = 120.seconds
    }
}
