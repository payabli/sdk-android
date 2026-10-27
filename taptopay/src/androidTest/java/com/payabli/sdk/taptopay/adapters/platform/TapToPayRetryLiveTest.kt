package com.payabli.sdk.taptopay.adapters.platform

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.taptopay.ChargeKeyStore
import com.payabli.sdk.taptopay.ManualDeviceTest
import com.payabli.sdk.taptopay.TapToPayCapture
import com.payabli.sdk.taptopay.TapToPayChargeRunner
import com.payabli.sdk.taptopay.TapToPayException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.ReaderCredentials
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.enrollment.platform.LiveRunSettings
import com.payabli.sdk.taptopay.enrollment.platform.LiveTapToPay
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPRoutes
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.provider.CardReadOutcome
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionException
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 300.seconds

private val AMOUNT = BigDecimal("1.00")

/**
 * A sale whose close answer is lost, then its declared retry, which the service refuses as the same sale before a card
 * is asked for. Run as [TapToPayChargeLiveTest] is; the card is stubbed, so nothing is charged.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceTest
class TapToPayRetryLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Comes up, and answers a tap with an approval, as the stub in [ChargeWithoutTapLiveTest] does. */
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

    @Before
    fun requireHardware() = LiveTapToPay.requireWiredHandset("charging needs a device the service has attested")

    @Test
    fun aDeclaredRetryOfAnUnconfirmedSaleIsNotASecondSale() =
        runTest(timeout = TEST_TIMEOUT) {
            withContext(Dispatchers.IO) {
                val reader = StubReader()
                val session = LiveTapToPay.session(context)
                val transport = LosesCloses(session.transport)
                val manager = TapToPaySessionManager()
                val coordinator =
                    TapToPaySessionCoordinator(
                        entry = LiveRunSettings.entry,
                        enrollment = LiveTapToPay.enrollment(context),
                        client = DeviceServiceClient(session.transport),
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
                val runner =
                    TapToPayChargeRunner(
                        entry = LiveRunSettings.entry,
                        environment = LiveRunSettings.environment,
                        coordinator = coordinator,
                        manager = manager,
                        reader = reader,
                        contactlessEnabled = { true },
                        client = TTPTransactionClient(transport),
                        store = AttestedDeviceStore(DeviceTrust.open(context).store),
                        keys = ChargeKeyStore(elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos),
                    )
                val details = TapToPayPaymentDetails(AMOUNT)

                val unconfirmed =
                    runCatching { runner.charge(details, LiveTapToPay.PAYER, TapToPayInvoiceData(), null) }
                        .exceptionOrNull() as? TapToPayException
                transport.losesCloses = false
                val retried =
                    runCatching {
                        runner.charge(details, LiveTapToPay.PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                    }.exceptionOrNull() as? TapToPayException
                val sale = unconfirmed?.paymentTransId
                Log.i(
                    LiveTapToPay.LIVE_TAG,
                    "unconfirmed sale: sale=$sale capture=${unconfirmed?.capture} retry=${retried?.type} " +
                        "openings=${transport.openingKeys.size} keys=${transport.openingKeys.distinct().size}",
                )

                assertEquals(
                    "the sale was not left taken and unconfirmed",
                    TapToPayCapture.CHARGED,
                    unconfirmed?.capture,
                )
                assertEquals(
                    "the retry was not refused as the same sale",
                    PayabliErrorType.CHARGE_NOT_FINISHED,
                    retried?.type,
                )
                assertEquals("the retry did not resend the held key", 1, transport.openingKeys.distinct().size)
                assertNotNull("the unconfirmed sale named no transaction", sale)
            }
        }

    /** Hands every request on, and while [losesCloses] holds, discards each close answer once the service gave it. */
    private class LosesCloses(
        private val inner: PayabliTransport,
    ) : PayabliTransport {
        @Volatile
        var losesCloses = true

        /** The key each opening was sent under, in order. */
        val openingKeys: MutableList<String> = mutableListOf()

        override suspend fun execute(request: PayabliRequest): PayabliResponse {
            request.headers["idempotencyKey"]?.let { openingKeys += it }
            val response = inner.execute(request)
            if (losesCloses && request.route == TTPRoutes.UPDATE) {
                throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "the close answer was lost")
            }
            return response
        }

        override suspend fun <T> execute(
            request: PayabliRequest,
            payloadSerializer: KSerializer<T>,
        ): PayabliV2Envelope<T> {
            request.headers["idempotencyKey"]?.let { openingKeys += it }
            val answer = inner.execute(request, payloadSerializer)
            if (losesCloses && request.route == TTPRoutes.UPDATE) {
                throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "the close answer was lost")
            }
            return answer
        }
    }
}
