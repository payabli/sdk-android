package com.payabli.example.app.ui

import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.payment.PaymentFormConfiguration
import com.payabli.example.app.preflight.CheckStatus
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.ui.setup.SetupViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Recheck" must read the device again.
 *
 * NFC is a Settings toggle, and the readiness check is the thing that tells a reader to go and flip
 * it. Against a snapshot taken at process start, the button re-ran the same five checks on the same
 * facts and gave the same answer, so the one action offered for the problem appeared to do nothing.
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
            formConfiguration = PaymentFormConfiguration.storePaymentMethod(),
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
