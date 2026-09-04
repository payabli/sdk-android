package com.payabli.sdk.taptopay.network.platform

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.platform.AttestorFactory
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.DeviceEnrollment
import com.payabli.sdk.taptopay.enrollment.EnrollmentOutcome
import com.payabli.sdk.taptopay.enrollment.platform.ActivationCodeMinter
import com.payabli.sdk.taptopay.enrollment.platform.DeviceDescriptionFactory
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.provider.CardReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 120.seconds

/**
 * The two MoneyIn calls against the real service, with no card and no reader.
 *
 * **Both calls carry a bearer and nothing else** — no assertion headers, unlike the device routes — so the
 * whole bracket can be driven without a tap. That is what makes this tier possible at all, and it is the
 * only thing that can show the request bodies are the ones the service accepts. Everything the unit tier
 * asserts about them is asserted against this SDK's own idea of the wire.
 *
 * **Not against sandbox.** A card-present opening cannot send `forceCustomerCreation`, and the sandbox
 * paypoint refuses a body that names no identifiable customer, so the same request is accepted on another
 * environment and refused there. The refusal is the paypoint's rule, not a defect here.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=<name> \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.network.platform.TTPTransactionLiveTest
 * ```
 *
 * An environment beyond `sandbox` and `production` is not in this checkout. Add it to the build first,
 * with `payabli.sdk.extraEnvironments=<name>=https://<host>.payabli.com` in
 * `~/.gradle/gradle.properties`, or `payabli.ttp.environment` names one the SDK does not carry and the
 * run stops at configuration.
 *
 * **Every run costs something real.** Opening is not repeatable, so each run leaves one more transaction
 * behind on the paypoint and nothing here removes them. The first run on a handset also enrols and spends
 * an activation attempt; later runs are warm.
 *
 * A paypoint that is not enabled for card-present payments fails both tests as
 * [TTPTransactionException.NotEnabled]. That is the account needing a change rather than anything here
 * being wrong.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class TTPTransactionLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() {
        // Fails rather than skips. This class is only ever invoked by name, so reaching it on an emulator
        // means the run was pointed at the wrong target, and a skip there reads as a run that went fine.
        // The two calls need no hardware; the activated device they charge as does, because getting one
        // means attesting. Measured on a non-Play-Store image: the run dies at the platform verdict with
        // RemediationRequired(-14), which reads like a service problem and is not one.
        assertFalse(
            "the live tier is for wired handsets: charging needs a device the service has attested",
            Build.HARDWARE in EMULATED,
        )
    }

    @After
    fun forgetTheDevice() =
        runTest(timeout = TEST_TIMEOUT) {
            // The service's row stays: it is what the next run is recognised by, and what keeps a later run
            // from spending another activation attempt.
            AttestedDeviceStore(DeviceTrust.open(context).store).clear(LiveRunSettings.entry)
        }

    @Test
    fun openingATransactionMintsAnIdentifier() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val paymentTransId = open()

                assertTrue("no identifier came back", paymentTransId.isNotBlank())
                Log.i(LIVE_TAG, "opened paymentTransId=$paymentTransId entry=${LiveRunSettings.entry}")
            }
        }

    /**
     * The transaction the opening minted is one the service recognises, and an invented one is not.
     *
     * With no tap there is no outcome anywhere, so a close here fails either way — once because the
     * transaction is not recognised at all, and once because a transaction that is recognised has no
     * outcome to report. Both arrive as an HTTP 400, so neither one alone says which happened.
     *
     * **Asserted as a difference rather than against any wording.** Matching the text that separates them
     * would put a decode table for someone else's messages in a public repository, which is the thing the
     * activation path is already trying to get away from. An inequality does not: whatever the two say,
     * they have to say different things, and the day they stop differing is the day the identifier stopped
     * being recognised.
     *
     * What that proves is the whole of what can be proven without a card — the verb, the path, the
     * credential and the identifier are the ones the service accepts, and the request got as far as the
     * lookup.
     */
    @Test
    fun closingIsRecognisedForARealTransactionAndNotForAnInventedOne() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val paymentTransId = open()

                val real = closing(paymentTransId)
                // The same shape, certainly not minted: only the last character differs, so nothing but the
                // identity of the transaction separates this call from the one above.
                val invented = closing(paymentTransId.dropLast(1) + if (paymentTransId.last() == '0') '1' else '0')

                Log.i(LIVE_TAG, "closed $paymentTransId with $real; the invented one answered $invented")
                assertFalse("the paypoint is not enabled for card-present payments", real.startsWith(NOT_ENABLED))
                assertFalse("the paypoint is not enabled for card-present payments", invented.startsWith(NOT_ENABLED))
                assertNotEquals(
                    "the same answer came back for a transaction that was opened and one that never existed",
                    invented,
                    real,
                )
            }
        }

    /** Closes [paymentTransId] and renders what the service answered, as one comparable line. */
    private suspend fun closing(paymentTransId: String): String {
        val outcome =
            runCatching {
                client().update(
                    paymentTransId,
                    // A reader never ran, so nothing this body holds can change the outcome. It is here
                    // because the body is part of the request shape under test.
                    CardReadResult(cardNetwork = null, providerResponse = "{}"),
                )
            }.exceptionOrNull() ?: return "accepted"
        return when (outcome) {
            is TTPTransactionException.NotEnabled -> NOT_ENABLED
            is PayabliException -> "${outcome.code}: ${outcome.detail ?: outcome.reason}"
            else -> "${outcome.javaClass.simpleName}: ${outcome.message}"
        }
    }

    /** Opens one transaction against the live paypoint and answers with its identifier. */
    private suspend fun open(): String =
        client().initiate(
            entryPoint = LiveRunSettings.entry,
            deviceId = activatedDeviceId(),
            paymentDetails = TapToPayPaymentDetails(BigDecimal("1.00")),
        )

    private suspend fun client(): TTPTransactionClient = TTPTransactionClient(session().transport)

    /**
     * The device identifier for this handset, enrolling and activating it when there is not one yet.
     *
     * A device is recognised by the handset's own stable identity, so the first run on a handset registers
     * and activates and every later run is warm. A fresh identity per run would spend an activation attempt
     * every time and leave another registered device behind.
     */
    private suspend fun activatedDeviceId(): String {
        val enrollment = enrollment()
        val outcome = enrollment.enroll()
        val store = AttestedDeviceStore(DeviceTrust.open(context).store)
        val record = store.read(LiveRunSettings.entry) ?: error("the cold sequence recorded nothing to charge with")

        if (outcome is EnrollmentOutcome.Attested && outcome.activationRequired) {
            // Playing the merchant's part, as the activation tier does. The SDK cannot mint its own code,
            // and the route needs a device handle that exists only once it has registered.
            enrollment.confirmActivation(
                ActivationCodeMinter.mint(
                    baseUrl = LiveRunSettings.baseUrl,
                    entry = LiveRunSettings.entry,
                    deviceId = record.deviceId,
                ),
            )
        }
        return record.deviceId
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

    private suspend fun enrollment(): DeviceEnrollment {
        val trust = DeviceTrust.open(context)
        return DeviceEnrollment(
            entry = LiveRunSettings.entry,
            appId = context.packageName,
            client = DeviceServiceClient(session().transport),
            // Classic, to match the challenge the enrollment path builds. A standard attestor refuses one.
            attestor = AttestorFactory.classic(context, cloudProjectNumber()),
            deviceKey = trust.key,
            signer = DeviceAssertionSigner(trust.key),
            store = AttestedDeviceStore(trust.store),
            description = DeviceDescriptionFactory.create(context),
            dispatcher = Dispatchers.IO,
        )
    }

    private fun cloudProjectNumber(): Long =
        InstrumentationRegistry.getArguments().getString("cloudProjectNumber")?.toLongOrNull()
            ?: error("payabli.cloudProjectNumber is required for the live tier; pass -Ppayabli.cloudProjectNumber=<n>")

    private companion object {
        val EMULATED = setOf("ranchu", "goldfish")

        /** One tag for this tier, so a live run's output is one logcat filter. */
        const val LIVE_TAG = "PayabliLiveRun"

        /** The one outcome that is not a difference between two transactions but a switch nobody flipped. */
        const val NOT_ENABLED = "not enabled"
    }
}
