package com.payabli.sdk.taptopay.adapters.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.taptopay.ChargeKeyStore
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.TapToPayCapture
import com.payabli.sdk.taptopay.TapToPayChargeRefusal
import com.payabli.sdk.taptopay.TapToPayChargeRunner
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.TapToPayResult
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.provider.CardReadOutcome
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
 * Charges against the real service from an activated handset, with the reader stubbed. Each case opens one real
 * transaction for [AMOUNT] unless the opening is refused, and no case charges a card.
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
                outcome = CardReadOutcome.APPROVED,
                providerState = "CAPTURED",
            )
    }

    /** Comes up, and answers every tap with a read that did not complete, so no card is charged. */
    private class FailingReader : TapToPayProvider {
        override suspend fun checkEligibility() = Unit

        override suspend fun configure(credentials: ReaderCredentials) = Unit

        override suspend fun prepareReader() = Unit

        override suspend fun startReading(request: CardReadRequest): CardReadResult =
            throw CardReaderException.ReadFailed(null)
    }

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("charging needs a device the service has attested")

    @Test
    fun aPaymentIsOpenedAndClosedAgainstTheRealPaypoint() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val receipt = chargeWith(StubReader(), LiveTapToPay.PAYER)

                assertTrue("no identifier came back", receipt.paymentTransId.isNotBlank())
                Log.i(LiveTapToPay.LIVE_TAG, "opened and closed one transaction")
            }
        }

    /**
     * A read that did not complete, closed against a payment the processor never saw because no card reached it.
     * The close is refused for that, and the charge reports the payment as not taken.
     */
    @Test
    fun aFailedReadTheProcessorNeverSawIsNotTaken() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val failure = runCatching { chargeWith(FailingReader(), LiveTapToPay.PAYER) }.exceptionOrNull()

                Log.i(LiveTapToPay.LIVE_TAG, "failed read: ${(failure as? TapToPayException)?.type ?: failure}")
                assertTrue("the charge did not fail as a Tap to Pay failure: $failure", failure is TapToPayException)
                val notTaken = failure as TapToPayException
                assertEquals(PayabliErrorType.PAYMENT_NOT_TAKEN, notTaken.type)
                assertEquals(TapToPayCapture.NOT_CHARGED, notTaken.capture)
            }
        }

    /**
     * A payer naming nobody, sent to a paypoint that requires a customer. The opening is refused with the fields
     * to collect, before the reader is asked for a card.
     */
    @Test
    fun aCustomerNamingNobodyIsRefusedWithTheFieldsToCollect() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val failure = runCatching { chargeWith(FailingReader(), TapToPayCustomerData()) }.exceptionOrNull()

                val refusal = (failure as? TapToPayException)?.refusal
                Log.i(LiveTapToPay.LIVE_TAG, "nobody named: ${(failure as? TapToPayException)?.type ?: failure}")
                assertTrue(
                    "the customer was not refused: $failure",
                    refusal is TapToPayChargeRefusal.CustomerDataRefused,
                )
                assertTrue(
                    "the refusal named no field to collect",
                    (refusal as TapToPayChargeRefusal.CustomerDataRefused).requiredFields.isNotEmpty(),
                )
                assertEquals(TapToPayCapture.NOT_CHARGED, (failure as TapToPayException).capture)
            }
        }

    /**
     * Brings a session up against the real service, activating this device first when it asks, and charges
     * [AMOUNT] through [reader].
     *
     * @param reader what answers the tap.
     * @param payer the customer the charge names.
     * @return the receipt for the charge.
     */
    private suspend fun chargeWith(
        reader: TapToPayProvider,
        payer: TapToPayCustomerData,
    ): TapToPayResult {
        val session = LiveTapToPay.session(context)
        val transport = session.transport
        val manager = TapToPaySessionManager()
        val coordinator =
            TapToPaySessionCoordinator(
                entry = LiveRunSettings.entry,
                enrollment = LiveTapToPay.enrollment(context),
                client = DeviceServiceClient(transport),
                reader = reader,
                manager = manager,
                renewToken = session::renewRefusedToken,
                isLive = { session.isLive },
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

        return TapToPayChargeRunner(
            entry = LiveRunSettings.entry,
            environment = LiveRunSettings.environment,
            coordinator = coordinator,
            manager = manager,
            reader = reader,
            contactlessEnabled = { true },
            client = TTPTransactionClient(transport),
            store = AttestedDeviceStore(DeviceTrust.open(context).store),
            keys = ChargeKeyStore(elapsedRealtimeNanos = android.os.SystemClock::elapsedRealtimeNanos),
        ).charge(
            TapToPayPaymentDetails(AMOUNT),
            payer,
            TapToPayInvoiceData(),
            null,
        )
    }
}
