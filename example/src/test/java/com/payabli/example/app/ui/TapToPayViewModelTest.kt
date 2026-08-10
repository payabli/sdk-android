package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.terminal.DemoTerminalController
import com.payabli.example.app.terminal.TerminalSessionState
import com.payabli.example.app.ui.taptopay.TapToPayViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What this screen's view model owns, none of which the controller's own tests can reach: parsing
 * the amount someone typed, turning an action's outcome into the one result line, holding the
 * buttons while an action runs, and keeping the session, the events and the ready flag in step with
 * the terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TapToPayViewModelTest {
    /** `viewModelScope` dispatches on `Dispatchers.Main`, which does not exist on a host JVM. */
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    private val target = TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator)

    private fun TestScope.model() =
        TapToPayViewModel(
            terminal = DemoTerminalController(stepDelayMillis = 0),
            // The client's own IO dispatcher, so the probe finishes before the assertion reads the
            // state. Left on Dispatchers.IO the socket work lands on a real thread and the test sees
            // "Checking…", which is the line the probe starts with.
            tokenClient = TokenServerClient(target, ioDispatcher = UnconfinedTestDispatcher(testScheduler)),
            configuration =
                DemoConfiguration(
                    "test6",
                    "com.payabli.example.app",
                    "AB:CD",
                    DemoEnvironment.SANDBOX,
                    true,
                ),
            readDeviceFacts = { facts },
            tokenServer = target,
        )

    /** A device on which the readiness checks are beside the point; this class is about the rest. */
    private val facts =
        DeviceFacts(
            isEmulator = false,
            model = "Pixel 8",
            apiLevel = 34,
            hasNfcHardware = true,
            isNfcEnabled = true,
            playServicesInstalled = true,
            playStoreInstalled = true,
            packageName = "com.payabli.example.app",
            signingCertificateDigest = "AB:CD",
        )

    // --- the amount ---

    @Test
    fun `an amount that will not parse says so, and charges nothing`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            viewModel.setAmount("twelve pounds")
            viewModel.charge()

            val text = viewModel.uiState.value.resultText
            assertTrue("reads as a success: $text", text.startsWith("✗"))
            assertFalse("charged anyway", text.contains("demo-txn"))
        }

    @Test
    fun `a blank amount is not treated as zero`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            viewModel.setAmount("   ")
            viewModel.charge()
            assertTrue(
                viewModel.uiState.value.resultText
                    .startsWith("✗"),
            )
        }

    /** Started and activated, which is what a charge needs from the demo device. */
    private fun TapToPayViewModel.readyTerminal() {
        enableTerminal()
        setActivationCode("ANY-CODE")
        activate()
    }

    @Test
    fun `a well-formed amount charges and reports the transaction`() =
        runTest {
            val viewModel = model()
            viewModel.readyTerminal()
            viewModel.setAmount("12.34")
            viewModel.charge()

            val text = viewModel.uiState.value.resultText
            assertTrue("did not charge: $text", text.contains("demo-txn"))
            assertTrue(text.startsWith("✓"))
        }

    @Test
    fun `charging before the terminal is on reports the failure`() =
        runTest {
            // The button is disabled for this, so the state cannot be reached by tapping. The model
            // still has to answer for it, because nothing here guarantees the button is the only
            // caller.
            val viewModel = model()
            viewModel.setAmount("1.00")
            viewModel.charge()
            assertTrue(
                viewModel.uiState.value.resultText
                    .startsWith("✗"),
            )
        }

    // --- actions ---

    @Test
    fun `a rejected activation code reports the failure and closes the sheet`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            viewModel.openActivation()
            viewModel.setActivationCode(DemoTerminalController.REJECTED_ACTIVATION_CODE)
            viewModel.activate()

            assertFalse("the sheet stayed open", viewModel.uiState.value.isActivationOpen)
            assertTrue(
                viewModel.uiState.value.resultText
                    .startsWith("✗"),
            )
        }

    @Test
    fun `an accepted activation code reports the success`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            viewModel.setActivationCode("ABC123")
            viewModel.activate()
            assertTrue(
                viewModel.uiState.value.resultText
                    .startsWith("✓"),
            )
        }

    @Test
    fun `a rejected activation is recorded for its own step, and cleared by a retry`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()

            viewModel.setActivationCode(DemoTerminalController.REJECTED_ACTIVATION_CODE)
            viewModel.activate()
            val refused = viewModel.uiState.value
            assertNotNull("the step has nothing to report", refused.activationFailure)
            assertTrue("the reason is not the failure", refused.activationFailure!!.startsWith("✗"))
            assertFalse("a refused device counted as activated", refused.activated)

            viewModel.setActivationCode("ANY-CODE")
            viewModel.activate()
            val accepted = viewModel.uiState.value
            assertNull("the old failure outlived the retry", accepted.activationFailure)
            assertTrue("a completed activation was not recorded", accepted.activated)
        }

    @Test
    fun `a failed charge is recorded for its own step, and cleared by one that works`() =
        runTest {
            val viewModel = model()
            viewModel.readyTerminal()

            // Rejected before the terminal is reached, which is the path that returns early.
            viewModel.setAmount("not an amount")
            viewModel.charge()
            assertNotNull("the step has nothing to report", viewModel.uiState.value.chargeFailure)

            viewModel.setAmount("0")
            viewModel.charge()
            assertNotNull("a refused amount left the step clean", viewModel.uiState.value.chargeFailure)

            viewModel.setAmount("12.34")
            viewModel.charge()
            assertNull("the old failure outlived the charge that worked", viewModel.uiState.value.chargeFailure)
        }

    @Test
    fun `a failure on one action does not become another step's failure`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            viewModel.setActivationCode(DemoTerminalController.REJECTED_ACTIVATION_CODE)
            viewModel.activate()
            assertNotNull(viewModel.uiState.value.activationFailure)

            // Anything else running must leave that alone: the step it belongs to is still failed.
            viewModel.reinitialize()

            assertNotNull(
                "the activation failure was cleared by another action",
                viewModel.uiState.value.activationFailure,
            )
            assertNull("that action's outcome landed on the charge step", viewModel.uiState.value.chargeFailure)
        }

    @Test
    fun `the buttons are released once an action finishes`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            assertFalse("left disabled", viewModel.uiState.value.isWorking)
        }

    // --- the terminal's own state ---

    @Test
    fun `the session and the ready flag follow the terminal`() =
        runTest {
            val viewModel = model()
            assertEquals(TerminalSessionState.Idle, viewModel.uiState.value.session)
            assertFalse(viewModel.uiState.value.isReady)

            // The demo device is unregistered, so starting the terminal stops at the step that
            // asks for a code, and only activating it reaches ready.
            viewModel.enableTerminal()
            assertEquals(TerminalSessionState.PendingActivation, viewModel.uiState.value.session)
            assertFalse(viewModel.uiState.value.isReady)

            viewModel.setActivationCode("ANY-CODE")
            viewModel.activate()
            assertEquals(TerminalSessionState.Ready, viewModel.uiState.value.session)
            assertTrue(viewModel.uiState.value.isReady)
        }

    @Test
    fun `events arrive and can be cleared`() =
        runTest {
            val viewModel = model()
            viewModel.enableTerminal()
            assertFalse("nothing was recorded", viewModel.uiState.value.events.isEmpty)

            viewModel.clearEvents()
            assertTrue(viewModel.uiState.value.events.isEmpty)
        }

    // --- the token probe ---

    @Test
    fun `a probe against nothing listening reports unreachable`() =
        runTest {
            // Port 1 on loopback: privileged, and nothing in this test binds it.
            val viewModel = model()
            viewModel.probeToken()

            val text = viewModel.uiState.value.tokenProbeText
            assertTrue("still mid-flight: $text", text.startsWith("✗"))
            assertFalse("left disabled", viewModel.uiState.value.isWorking)
        }
}
