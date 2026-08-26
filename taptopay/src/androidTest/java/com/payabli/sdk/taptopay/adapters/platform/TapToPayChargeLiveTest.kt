package com.payabli.sdk.taptopay.adapters.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionException
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 300.seconds

private val AMOUNT = BigDecimal("1.00")

/**
 * A whole card-present payment: open at Payabli, tap, close.
 *
 * **Somebody has to be at the handset with a contactless card.** The reader waits for one, and the run ends
 * at the timeout above if nothing is presented.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=qa \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.adapters.platform.TapToPayChargeLiveTest
 * ```
 *
 * Every run charges a real paypoint for [AMOUNT] and leaves the transaction behind.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class TapToPayChargeLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("a tap is for wired handsets: an emulator has no reader")

    @Test
    fun aTapProducesACapturedTransaction() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val reader = CardReaders.fiserv(context)
                val transport = LiveTapToPay.session(context).transport
                val coordinator =
                    TapToPaySessionCoordinator(
                        entry = LiveRunSettings.entry,
                        enrollment = LiveTapToPay.enrollment(context),
                        client = DeviceServiceClient(transport),
                        reader = reader,
                        manager = TapToPaySessionManager(),
                    )

                val owed = runCatching { coordinator.initialize() }.exceptionOrNull()
                if (owed is TapToPaySessionException.PendingActivation) {
                    val record =
                        AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)
                            ?: error("the session asked for activation and recorded nothing to activate")
                    coordinator.activateDevice(LiveTapToPay.mintActivationCode(record.deviceId))
                    coordinator.initialize()
                } else if (owed != null) {
                    throw owed
                }
                assertEquals(TapToPaySessionState.Ready, coordinator.state.value)

                val deviceId =
                    AttestedDeviceStore(DeviceTrust.open(context).store).read(LiveRunSettings.entry)?.deviceId
                        ?: error("the session reached ready with no device to charge as")
                val client = TTPTransactionClient(transport)
                val paymentTransId =
                    client.initiate(
                        entryPoint = LiveRunSettings.entry,
                        deviceId = deviceId,
                        paymentDetails = TapToPayPaymentDetails(AMOUNT),
                    )
                Log.i(LiveTapToPay.LIVE_TAG, "opened paymentTransId=$paymentTransId; present a card now")

                val read =
                    runCatching {
                        reader.startReading(
                            CardReadRequest(
                                amount = AMOUNT,
                                merchantTransactionId = paymentTransId,
                                merchantOrderId = paymentTransId,
                                merchantInvoiceNumber = null,
                            ),
                        )
                    }
                val failure = read.exceptionOrNull()
                if (failure != null) {
                    // Sent so the opened transaction is not left standing. The run still fails.
                    client.updateAfterFailedRead(paymentTransId, failure.javaClass.simpleName)
                    throw failure
                }

                val result = read.getOrThrow()
                client.update(paymentTransId, result)
                Log.i(
                    LiveTapToPay.LIVE_TAG,
                    "closed $paymentTransId on ${result.cardNetwork ?: "an unnamed network"}",
                )

                assertTrue(
                    "the reader answered with an empty record",
                    result.providerResponse.length > "{}".length,
                )
            }
        }
}
