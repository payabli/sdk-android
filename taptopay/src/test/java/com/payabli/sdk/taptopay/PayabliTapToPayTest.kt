package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.FakeDeviceKey
import com.payabli.sdk.taptopay.enrollment.FakeSecureStore
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.activateBody
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.registerBody
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.approved
import com.payabli.sdk.taptopay.session.SessionFixture
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Failed
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val TRANS_ID = "12-abc"

/** A named payer, because the public call takes one: an opening that identifies nobody is refused. */
private val PAYER = TapToPayCustomerData(firstName = "Ada", lastName = "Payer", customerNumber = "cust-1")

/**
 * The public surface: what each call does to [PayabliTTP.sessionState], and what a host is given when one
 * fails.
 */
class PayabliTapToPayTest {
    @org.junit.Before
    @org.junit.After
    fun forgetHeldKeys() = ChargeKeyStore.forgetHeld()

    private fun terminalOver(fixture: SessionFixture) =
        PayabliTTP.over(
            coordinator = fixture.coordinator,
            runner =
                TapToPayChargeRunner(
                    entry = ENTRY,
                    environment = PayabliEnvironment.SANDBOX,
                    coordinator = fixture.coordinator,
                    manager = fixture.manager,
                    reader = fixture.reader,
                    client = TTPTransactionClient(fixture.enrollment.transport, fixture.enrollment.logger),
                    store = fixture.enrollment.store,
                    keys = fixture.keys,
                ),
        )

    private fun script(registerStatus: String = "active") =
        RouteScript(
            RouteScript.CHALLENGE to listOf(challengeBody()),
            RouteScript.REGISTER to listOf(registerBody(status = registerStatus)),
            RouteScript.ATTEST to listOf(attestBody()),
            RouteScript.CONFIG to listOf(configBody()),
            RouteScript.ACTIVATE to listOf(activateBody()),
            "/api/v2/MoneyIn/initiate" to listOf(approved("""{"paymentTransId":"$TRANS_ID"}""")),
            "/api/v2/MoneyIn/update/$TRANS_ID" to listOf("{}"),
        )

    @Test
    fun `initialize walks the phases and lands ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)

            terminal.initialize()

            assertEquals(TapToPaySessionState.Ready, terminal.sessionState.value)
            assertTrue(terminal.isReady.value)
        }

    @Test
    fun `activating a device leaves the terminal idle, so setting it up comes next`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The contract a host depends on: activation approves the device and sets nothing up.
            val fixture = SessionFixture(script(registerStatus = "pending"))
            val terminal = terminalOver(fixture)
            runCatching { terminal.initialize() }

            terminal.activateDevice("123456")

            assertEquals(TapToPaySessionState.Idle, terminal.sessionState.value)
            assertFalse(terminal.isReady.value)
        }

    @Test
    fun `a device that owes a code says so rather than failing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script(registerStatus = "pending"))
            val terminal = terminalOver(fixture)

            val failure = runCatching { terminal.initialize() }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(TapToPaySessionState.PendingActivation, terminal.sessionState.value)
        }

    @Test
    fun `a charge answers with the identifier the payment was opened under`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)
            terminal.initialize()

            val result = terminal.charge(TapToPayPaymentDetails(BigDecimal("12.34")), PAYER)

            assertEquals(TRANS_ID, result.paymentTransId)
        }

    @Test
    fun `every failure reaches a host as one type`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A host catches one thing and reads the reason off the state, so a raw internal exception
            // escaping here would be a surface an integrator has to learn.
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)
            terminal.initialize()

            val failure =
                runCatching { terminal.charge(TapToPayPaymentDetails(BigDecimal.ZERO), PAYER) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
        }

    @Test
    fun `a failure refused at the facade is a PayabliException carrying its classification, not its prose`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)
            terminal.initialize()

            val failure =
                runCatching { terminal.charge(TapToPayPaymentDetails(BigDecimal.ZERO), PAYER) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is PayabliException)
            val thrown = failure as TapToPayException
            assertEquals(PayabliErrorCode.UNKNOWN, thrown.code)
            assertEquals(thrown.code.wireName, thrown.message)
            assertTrue(thrown.cause.toString(), thrown.cause is IllegalArgumentException)
            assertEquals(thrown.cause?.message, thrown.reason)
        }

    @Test
    fun `a failure during a charge is a PayabliException carrying its classification, not its prose`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)
            terminal.initialize()
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    terminal.charge(TapToPayPaymentDetails(BigDecimal("12.34")), PAYER)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is PayabliException)
            val thrown = failure as TapToPayException
            assertEquals(PayabliErrorCode.UNKNOWN, thrown.code)
            assertEquals(thrown.code.wireName, thrown.message)
            assertTrue(thrown.cause.toString(), thrown.cause is CardReaderException.ReadFailed)
            assertEquals(TRANS_ID, thrown.paymentTransId)
        }

    @Test
    fun `a fresh install reads no device id`() =
        runTest(timeout = TEST_TIMEOUT) {
            val terminal = terminalOver(SessionFixture(script()))

            assertNull(terminal.deviceId())
        }

    @Test
    fun `a device that owes a code reads the id it owes it under`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The host's backend needs this id to request the code, so it has to be readable while pending.
            val fixture = SessionFixture(script(registerStatus = "pending"))
            val terminal = terminalOver(fixture)
            runCatching { terminal.initialize() }
            assertEquals(TapToPaySessionState.PendingActivation, terminal.sessionState.value)

            assertEquals(DEVICE_ID, terminal.deviceId())
        }

    @Test
    fun `a store that cannot be read reads as no device id, without throwing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    script(),
                    storeFailure = FakeSecureStore.failing("get", SecureStorageException.StorageUnavailable()),
                )
            fixture.seedRecord()
            val terminal = terminalOver(fixture)

            assertNull(terminal.deviceId())
        }

    @Test
    fun `a key store that cannot confirm the key keeps the binding, throws, and names the reason`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    script(),
                    deviceKey = FakeDeviceKey(publicKeyFailure = DeviceKeyException.CryptoUnavailable()),
                )
            fixture.seedRecord()
            val terminal = terminalOver(fixture)

            val failure = runCatching { terminal.initialize() }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(Failed(TapToPayFailureReason.DEVICE_KEY_UNAVAILABLE), terminal.sessionState.value)
            assertNotNull("the binding was discarded", fixture.enrollment.storedRecord())
            assertTrue("a request was sent", fixture.routes.isEmpty())
        }

    @Test
    fun `a failure reaching a host still names the payment`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The facade builds the failure a host sees, so a payment named underneath it has to survive
            // that step. Rebuilding the failure here is what would drop the name.
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)
            terminal.initialize()
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    terminal.charge(TapToPayPaymentDetails(BigDecimal("12.34")), PAYER)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(TRANS_ID, (failure as TapToPayException).paymentTransId)
        }

    @Test
    fun `isReady falls the moment the session leaves ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())
            val terminal = terminalOver(fixture)
            terminal.initialize()

            fixture.manager.invalidate()

            assertEquals(TapToPaySessionState.SessionExpired, terminal.sessionState.value)
            assertFalse(terminal.isReady.value)
        }
}
