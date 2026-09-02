package com.payabli.example.app.demo.ui

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.SimpleCaptureSetting
import com.payabli.example.app.demo.config.TokenHostSource
import com.payabli.example.app.demo.config.TokenServerTarget
import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.example.app.demo.preflight.CheckStatus
import com.payabli.example.app.demo.preflight.DeviceFacts
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.setup.SetupViewModel
import com.payabli.example.app.sdk.DemoEnvironment
import com.payabli.example.app.sdk.PayInForms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Recheck" must read the device again.
 *
 * NFC is a Settings toggle, and this check is what tells a reader to go and flip it. Against a
 * snapshot taken at process start the button gives the same answer every time, so the one action
 * offered for the problem does nothing.
 */
class RecheckReadsTheDeviceAgainTest {
    private val appId = "com.payabli.example.app"

    private fun facts(nfcEnabled: Boolean) =
        DeviceFacts(
            isEmulator = false,
            model = "Pixel 8",
            apiLevel = 34,
            hasNfcHardware = true,
            isNfcEnabled = nfcEnabled,
            playServicesInstalled = true,
            playStoreInstalled = true,
            packageName = appId,
            signingCertificateDigest = "AB:CD",
        )

    private val target = TokenServerTarget("http://127.0.0.1:8787", TokenHostSource.Emulator)

    private fun model(read: () -> DeviceFacts) =
        SetupViewModel(
            // The digest [facts] reports, so the readiness check is fully configured and a warning
            // in these tests can only have come from the device.
            configuration = DemoConfiguration("test6", appId, "AB:CD", DemoEnvironment.SANDBOX, true),
            tokenServer = target,
            tokenClient = TokenServerClient(target),
            readDeviceFacts = read,
            demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device")),
            simpleCapture = SimpleCaptureSetting(),
            formSetup = PayInForms.storePaymentMethod(),
        )

    @Test
    fun `turning NFC on between checks clears the warning`() {
        var nfcEnabled = false
        val viewModel = model { facts(nfcEnabled) }
        assertTrue(
            viewModel.uiState.value.problems
                .any { it.status == CheckStatus.Warn },
        )

        nfcEnabled = true
        viewModel.recheck()
        assertEquals(emptyList<Any>(), viewModel.uiState.value.problems)
    }

    @Test
    fun `the readout follows the device too, not only the verdict`() {
        // The Setup screen prints the facts beside the verdict, and the two disagreeing is worse
        // than either being stale on its own.
        var nfcEnabled = false
        val viewModel = model { facts(nfcEnabled) }
        nfcEnabled = true
        viewModel.recheck()
        assertTrue(viewModel.uiState.value.deviceFacts.isNfcEnabled)
    }

    @Test
    fun `NFC switched off again is seen`() {
        var nfcEnabled = true
        val viewModel = model { facts(nfcEnabled) }
        assertEquals(emptyList<Any>(), viewModel.uiState.value.problems)

        nfcEnabled = false
        viewModel.recheck()
        assertTrue(
            viewModel.uiState.value.problems
                .any { it.status == CheckStatus.Warn },
        )
    }
}
