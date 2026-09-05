package com.payabli.sdk.taptopay.network.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.provider.CardReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID
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
        // The two calls need no hardware; the activated device they charge as does, because getting one
        // means attesting. Measured on a non-Play-Store image: the run dies at the platform verdict with
        // RemediationRequired(-14), which reads like a service problem and is not one.
        LiveTapToPay.requireWiredHandset(
            "the live tier is for wired handsets: charging needs a device the service has attested",
        )
    }

    @Test
    fun openingATransactionMintsAnIdentifier() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val paymentTransId = open()

                assertTrue("no identifier came back", paymentTransId.isNotBlank())
                Log.i(LiveTapToPay.LIVE_TAG, "opened on entry=${LiveRunSettings.entry}")
            }
        }

    /**
     * A second opening under one key does not open a second transaction.
     *
     * The whole of what the key is for. Without it the two calls are two sales, and a caller that lost the
     * first answer and asked again has charged the payer twice.
     *
     * **Asserted as a difference, not against any wording**, for the reason the closing test is: matching
     * the text would put a decode table for someone else's messages in a public repository. The first call
     * answers with an identifier and the second does not answer at all, which is the difference that
     * matters, and the day the second one succeeds is the day the suppression stopped.
     *
     * One transaction is left behind rather than two, so this costs the paypoint less than the tests above.
     */
    @Test
    fun openingTwiceUnderOneKeyOpensOneTransaction() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val key = UUID.randomUUID().toString()

                val first = open(idempotencyKey = key)
                val repeat = runCatching { open(idempotencyKey = key) }

                // Neither identifier is logged, for the reason the opening test gives: an identifier names a
                // live transaction and this runs against a real paypoint. Whether the repeat was refused is
                // the whole of what this test is asking.
                Log.i(
                    LiveTapToPay.LIVE_TAG,
                    "the repeat under one key was refused: ${repeat.isFailure}",
                )
                assertTrue("the first opening minted no identifier", first.isNotBlank())
                assertNotEquals(
                    "a repeat under one key opened a second transaction",
                    first,
                    repeat.getOrNull(),
                )
                assertTrue(
                    "a repeat under one key was carried out instead of being refused",
                    repeat.isFailure,
                )
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

                // Neither the identifier nor either answer: `closing` returns what the service said, which
                // carries a reason and a detail. The assertions below hold both values.
                Log.i(LiveTapToPay.LIVE_TAG, "closed the real transaction and the invented one")
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

    /**
     * Opens one transaction against the live paypoint and answers with its identifier.
     *
     * A fresh key per call unless [idempotencyKey] names one, so every test here opens its own transaction
     * except the one asserting that a repeat under one key does not.
     */
    private suspend fun open(idempotencyKey: String = UUID.randomUUID().toString()): String =
        client().initiate(
            entryPoint = LiveRunSettings.entry,
            deviceId = LiveTapToPay.activatedDeviceId(context),
            paymentDetails = TapToPayPaymentDetails(BigDecimal("1.00")),
            idempotencyKey = idempotencyKey,
        )

    private suspend fun client(): TTPTransactionClient = TTPTransactionClient(LiveTapToPay.session(context).transport)

    private companion object {
        /** The one outcome that is not a difference between two transactions but a switch nobody flipped. */
        const val NOT_ENABLED = "not enabled"
    }
}
