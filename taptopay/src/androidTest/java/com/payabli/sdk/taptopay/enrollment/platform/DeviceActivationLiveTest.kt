package com.payabli.sdk.taptopay.enrollment.platform

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDevice
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.enrollment.DeviceDescription
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 120.seconds

/**
 * The whole sequence against the real service, on real hardware.
 *
 * Start `LocalTokenServer` on the development machine, then:
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.enrollment.platform.DeviceActivationLiveTest
 * ```
 *
 * `payabli.cloudProjectNumber` comes from `~/.gradle/gradle.properties`. No token is passed: they are
 * fetched per call and expire quickly.
 *
 * Across the bench, and the `|| true` is load-bearing: without it the first red aborts the loop and the
 * devices that never ran report nothing at all, which reads exactly like a clean sweep.
 *
 * ```
 * for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
 *   ANDROID_SERIAL=$s ./gradlew :taptopay:connectedAndroidTest ... || true
 * done
 * ```
 *
 * **Three tests, not one**, so a failure names the call it happened at instead of collapsing the whole run
 * into a single red line, and so the two that need no attestation still report when the third cannot run.
 *
 * **Every run costs something real.** Each spends a challenge, and the third spends one of five activation
 * attempts. Play Integrity also throttles well below its daily budget when a handful of devices are driven
 * in a row. Wait it out; do not re-run.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class DeviceActivationLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() {
        // A denylist, so an unrecognized emulator fails visibly instead of dropping this tier's coverage.
        // The software-level answers belong to the unit tier.
        assumeFalse(
            "the live tier is for wired handsets; an emulator's device key is software-backed",
            Build.HARDWARE in EMULATED,
        )
    }

    @After
    fun forgetTheDevice() =
        runTest(timeout = TEST_TIMEOUT) {
            // The service's row stays: it is what the next run is recognised by.
            AttestedDeviceStore(DeviceTrust.open(context).store).clear()
        }

    @Test
    fun theChallengeAndRegisterCallsSucceedAgainstTheRealService() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val client = DeviceServiceClient(session().transport)
                val challenge = client.challenge(LiveRunSettings.entry)
                assertTrue(challenge.challenge.isNotBlank())

                val trust = DeviceTrust.open(context)
                val registration =
                    client.register(
                        entry = LiveRunSettings.entry,
                        hardwareId = DeviceDescriptionFactory.create(context).hardwareId,
                        keyId = trust.key.publicKey().identity,
                        deviceName = null,
                        model = Build.MODEL,
                        osVersion = Build.VERSION.RELEASE,
                    )
                assertTrue(registration.deviceId.isNotBlank())

                // The correlation key for the service's own logs, which record DeviceId on every branch.
                // Printed only by this tier: the shipped code never logs it, and the field name is not on
                // the loggable allowlist.
                Log.i(LIVE_TAG, "registered deviceId=${registration.deviceId} entry=${LiveRunSettings.entry}")
            }
        }

    @Test
    fun theColdSequenceCompletesThroughAttest() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val outcome = diagnosing { enrollment().enroll() }

                // A device the service already holds as active answers false, which is also a pass.
                assertNotNull(outcome)
                assertNotNull(AttestedDeviceStore(DeviceTrust.open(context).store).read())
            }
        }

    /**
     * The refusal an unattested device gets, checked against the service's own wording.
     *
     * The failure taxonomy classifies `/activate` by matching the service's message text, and the unit tier
     * asserts that mapping against strings copied into the test. Only a live call shows the strings still
     * match what the service emits — this is the one refusal reachable without a completed attestation, so
     * it is the one that can be checked today.
     *
     * Costs no activation attempt: the assertion is verified before the code is compared, so this returns
     * at the attestation lookup and the five-attempt counter is untouched.
     */
    @Test
    fun anUnattestedDeviceIsRefusedWithTheWordingTheMapperExpects() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val trust = DeviceTrust.open(context)
                val client = DeviceServiceClient(session().transport)
                // Its own device row. Sharing the cold sequence's would mean sharing the attestation that
                // sequence writes, and `/register` keeps an attestation when the key is unchanged, so this
                // case would stop being unattested the moment `/attest` starts succeeding.
                val description = unattestedDescription()
                val registration =
                    client.register(
                        entry = LiveRunSettings.entry,
                        hardwareId = description.hardwareId,
                        keyId = trust.key.publicKey().identity,
                        deviceName = null,
                        model = Build.MODEL,
                        osVersion = Build.VERSION.RELEASE,
                    )

                // Stand in for the record a completed cold sequence would have left. The device is
                // registered but not attested, which is exactly the state under test.
                val store = AttestedDeviceStore(trust.store)
                store.write(
                    AttestedDevice(
                        entry = LiveRunSettings.entry,
                        deviceId = registration.deviceId,
                        keyId = trust.key.publicKey().identity,
                        activated = false,
                    ),
                )

                // Needed only to get past the guard that would otherwise answer "no active challenge"
                // before the attestation lookup is reached. Idempotent inside its own window.
                val code =
                    ActivationCodeMinter.mint(
                        baseUrl = LiveRunSettings.baseUrl,
                        accessToken = LiveRunSettings.accessToken(),
                        entry = LiveRunSettings.entry,
                        deviceId = registration.deviceId,
                    )

                val thrown =
                    runCatching { enrollment(description).confirmActivation(code) }.exceptionOrNull()

                Log.i(LIVE_TAG, "activate refused with ${thrown?.javaClass?.simpleName}")
                assertTrue(
                    "expected the revocation classification, got $thrown",
                    thrown is DeviceActivationException.AttestationRevoked,
                )
                // And the disposition that classification carries: the record is discarded, the key is not.
                assertNull(store.read())
                assertNotNull(trust.key.publicKey())
            }
        }

    @Test
    fun aPendingDeviceActivatesWithACodeMintedOutOfBand() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val enrollment = enrollment()
                val outcome = enrollment.enroll()
                val store = AttestedDeviceStore(DeviceTrust.open(context).store)
                val record = store.read() ?: error("the cold sequence recorded nothing to activate")

                assumeFalse(
                    "this device is already active at the service; nothing to activate",
                    !outcome.activationRequired,
                )

                // Minted here, playing the merchant's part, because the route needs a device handle that
                // only exists once the device has registered.
                val code =
                    ActivationCodeMinter.mint(
                        baseUrl = LiveRunSettings.baseUrl,
                        accessToken = LiveRunSettings.accessToken(),
                        entry = LiveRunSettings.entry,
                        deviceId = record.deviceId,
                    )

                enrollment.confirmActivation(code)

                val activated = store.read()
                assertNotNull(activated)
                assertTrue(activated!!.activated)
                assertFalse(enrollment.enroll().activationRequired)
            }
        }

    /**
     * Runs [body], and on a refusal fails with the service's own wording.
     *
     * The shipped types keep that wording off `toString`, because it can echo request data into a log. A
     * failing live run is the one place it is wanted: without it every refusal reads as a bare result code
     * and the next step is guesswork.
     */
    private suspend fun <T> diagnosing(body: suspend () -> T): T =
        try {
            body()
        } catch (declined: DeviceServiceException) {
            throw AssertionError("${declined.javaClass.simpleName}(${declined.resultCode}): ${declined.reason}")
        } catch (declined: DeviceActivationException) {
            throw AssertionError("${declined.javaClass.simpleName}(${declined.resultCode}): ${declined.reason}")
        }

    private suspend fun session(): PayabliSession =
        PayabliSession
            .initialize(
                PayabliConfig(
                    accessToken = LiveRunSettings.accessToken(),
                    entryPoint = LiveRunSettings.entry,
                    environment = LiveRunSettings.environment,
                    // What an integrator supplies: the SDK calls this when the service rejects the bearer.
                    // Without it a run dies at whatever call the token expired on, as TOKEN_EXPIRED out of
                    // the refresh path.
                    tokenProvider = { LiveRunSettings.accessToken() },
                ),
                HostBindings(context),
            ).getOrThrow()

    private suspend fun enrollment(
        description: DeviceDescription = DeviceDescriptionFactory.create(context),
    ): DeviceEnrollment {
        val trust = DeviceTrust.open(context)
        return DeviceEnrollment(
            entry = LiveRunSettings.entry,
            appId = context.packageName,
            client = DeviceServiceClient(session().transport),
            // Classic, and it has to be: the nonce the service issues derives a classic challenge, which a
            // standard attestor refuses outright.
            attestor = AttestorFactory.classic(context, cloudProjectNumber()),
            deviceKey = trust.key,
            signer = DeviceAssertionSigner(trust.key),
            store = AttestedDeviceStore(trust.store),
            description = description,
            dispatcher = Dispatchers.IO,
        )
    }

    /**
     * A second, stable device identity on the same paypoint, for the case that needs an unattested device.
     *
     * The service keys a device row on this value, and `/register` preserves an attestation already written
     * against a row when the key is unchanged. So a test that needs "no attestation exists" cannot share the
     * row the cold sequence attests, or it stops testing what it names as soon as `/attest` starts
     * succeeding. Derived rather than random, so it is one extra row on the paypoint and not one per run.
     */
    private fun unattestedDescription(): DeviceDescription {
        val base = DeviceDescriptionFactory.create(context)
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("${base.hardwareId}|unattested".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (index in 0 until 16) {
            hex.append(HEX[(digest[index].toInt() shr 4) and 0xF])
            hex.append(HEX[digest[index].toInt() and 0xF])
        }
        return DeviceDescription(hex.toString(), base.deviceName, base.model, base.osVersion)
    }

    private fun cloudProjectNumber(): Long =
        InstrumentationRegistry.getArguments().getString("cloudProjectNumber")?.toLongOrNull()
            ?: error("payabli.cloudProjectNumber is required for the live tier; pass -Ppayabli.cloudProjectNumber=<n>")

    private companion object {
        val EMULATED = setOf("ranchu", "goldfish")
        val HEX = "0123456789abcdef".toCharArray()

        /** One tag for this tier, so a live run's output is one logcat filter. */
        const val LIVE_TAG = "PayabliLiveRun"
    }
}
