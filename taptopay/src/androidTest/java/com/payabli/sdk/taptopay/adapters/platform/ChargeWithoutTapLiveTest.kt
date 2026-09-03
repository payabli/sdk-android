package com.payabli.sdk.taptopay.adapters.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.TapToPayChargeRunner
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 180.seconds

private val AMOUNT = BigDecimal("1.00")

/**
 * The whole charge against the real service, with the card stubbed.
 *
 * Everything a payment touches runs for real here except the tap: the session reaches ready, the payment is
 * opened, and it is closed with what the reader answered. Only the reader is stood in for, so this is the
 * furthest the charge can be driven without a person and a card.
 *
 * The unit tier asserts the same order against fakes. What this adds is the wire: that the service accepts
 * these two bodies, in this order, from a device it has activated.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :taptopay:connectedAndroidTest \
 *   -Ppayabli.ttp.entry=<entry> -Ppayabli.ttp.environment=qa \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.payabli.sdk.taptopay.adapters.platform.ChargeWithoutTapLiveTest
 * ```
 *
 * Every run opens and closes one real transaction on the paypoint for [AMOUNT].
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class ChargeWithoutTapLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Comes up, and answers a tap with the shape the transaction client forwards. */
    private class StubReader : TapToPayProvider {
        override suspend fun checkEligibility() = Unit

        override suspend fun configure(credentials: ReaderCredentials) = Unit

        override suspend fun prepareReader() = Unit

        override suspend fun startReading(request: CardReadRequest): CardReadResult =
            CardReadResult(
                cardNetwork = "VISA",
                providerResponse = """{"gatewayResponse":{"transactionState":"CAPTURED"}}""",
            )
    }

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("charging needs a device the service has attested")

    @Test
    fun aPaymentIsOpenedAndClosedAgainstTheRealPaypoint() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val transport = LiveTapToPay.session(context).transport
                val manager = TapToPaySessionManager()
                val coordinator =
                    TapToPaySessionCoordinator(
                        entry = LiveRunSettings.entry,
                        enrollment = LiveTapToPay.enrollment(context),
                        client = DeviceServiceClient(transport),
                        reader = StubReader(),
                        manager = manager,
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
                assertEquals(TapToPaySessionState.Ready, manager.state.value)

                val receipt =
                    TapToPayChargeRunner(
                        entry = LiveRunSettings.entry,
                        coordinator = coordinator,
                        manager = manager,
                        reader = StubReader(),
                        client = TTPTransactionClient(transport),
                        store = AttestedDeviceStore(DeviceTrust.open(context).store),
                    ).charge(
                        TapToPayPaymentDetails(AMOUNT),
                        TapToPayCustomerData(),
                        TapToPayInvoiceData(),
                        null,
                    )

                assertTrue("no identifier came back", receipt.paymentTransId.isNotBlank())
                Log.i(LiveTapToPay.LIVE_TAG, "opened and closed one transaction")
            }
        }
}
