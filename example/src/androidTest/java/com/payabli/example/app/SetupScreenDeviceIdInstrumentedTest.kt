package com.payabli.example.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.TokenHostSource
import com.payabli.example.app.demo.config.TokenServerTarget
import com.payabli.example.app.demo.preflight.DeviceFacts
import com.payabli.example.app.demo.ui.setup.SetupScreen
import com.payabli.example.app.demo.ui.setup.SetupUiState
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import com.payabli.example.app.sdk.DemoEnvironment
import com.payabli.example.app.sdk.PayInForms
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The setup screen shows the device ID support asks for, and copies it. */
@RunWith(AndroidJUnit4::class)
class SetupScreenDeviceIdInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSessionsDeviceIdIsShown() {
        show(deviceId = DEVICE_ID)

        compose.onNodeWithText(DEVICE_ID).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aMissingDeviceIdReadsUnavailableAndOffersNoCopy() {
        show(deviceId = null)

        compose.onNodeWithText("Unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(COPY).assertDoesNotExist()
    }

    @Test
    fun copyPutsTheDeviceIdOnTheClipboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Something else first: the clipboard outlives the test, and a previous run left the ID on it.
        instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("before", "before")) }
        show(deviceId = DEVICE_ID)

        compose.onNodeWithContentDescription(COPY).performScrollTo().performClick()
        compose.waitForIdle()

        var copied: CharSequence? = null
        instrumentation.runOnMainSync { copied = clipboard.primaryClip?.getItemAt(0)?.text }
        assertEquals(DEVICE_ID, copied?.toString())
    }

    private fun show(deviceId: String?) {
        val target = TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator)
        compose.setContent {
            PayabliDemoTheme {
                SetupScreen(
                    state =
                        SetupUiState(
                            configuration =
                                DemoConfiguration(
                                    entryPoint = "test6",
                                    appId = "com.payabli.example.app",
                                    signingCertificate = "",
                                    environment = DemoEnvironment.SANDBOX,
                                    diagnosticsEnabled = false,
                                ),
                            tokenServer = target,
                            deviceFacts =
                                DeviceFacts(
                                    isEmulator = true,
                                    model = "Test",
                                    apiLevel = 34,
                                    readerSupported = false,
                                    hasNfcHardware = false,
                                    isNfcEnabled = false,
                                    playServicesInstalled = true,
                                    playStoreInstalled = true,
                                    packageName = "com.payabli.example.app",
                                    signingCertificateDigest = null,
                                ),
                            formSetup = PayInForms.storePaymentMethod(),
                            deviceId = deviceId,
                        ),
                    onProbeToken = {},
                    onProbeHealth = {},
                    onRecheck = {},
                    onSuppliesDemoCustomerChange = {},
                    onShowSimpleCaptureChange = {},
                )
            }
        }
    }

    private companion object {
        const val DEVICE_ID = "a1b2c3d4e5f60718"
        const val COPY = "Copy Device ID"
    }
}
