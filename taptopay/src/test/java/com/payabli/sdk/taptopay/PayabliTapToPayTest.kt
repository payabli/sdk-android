package com.payabli.sdk.taptopay

import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.activateBody
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.registerBody
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.approved
import com.payabli.sdk.taptopay.session.SessionFixture
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val TRANS_ID = "12-abc"

/**
 * The public surface: what each call does to [PayabliTTP.sessionState], and what a host is given when one
 * fails.
 */
class PayabliTapToPayTest {
    private fun terminalOver(fixture: SessionFixture) =
        PayabliTTP(
            coordinator = fixture.coordinator,
            runner =
                TapToPayChargeRunner(
                    entry = ENTRY,
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

            val result = terminal.charge(TapToPayPaymentDetails(BigDecimal("12.34")))

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
                runCatching { terminal.charge(TapToPayPaymentDetails(BigDecimal.ZERO)) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
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
