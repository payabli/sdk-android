package com.payabli.example.app.demo.ui.setup

import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.SimpleCaptureSetting
import com.payabli.example.app.demo.config.TokenHostSource
import com.payabli.example.app.demo.config.TokenServerTarget
import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.example.app.demo.preflight.DeviceFacts
import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.sdk.DemoEnvironment
import com.payabli.example.app.sdk.PayInForms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The device ID support asks for, read from the session and carried to the setup screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelDeviceIdTest {
    /** `viewModelScope` dispatches on `Dispatchers.Main`, which does not exist on a host JVM. */
    @Before
    fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun removeMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun `the session's device ID reaches the screen`() {
        val state = model { Result.success("a1b2c3d4e5f60718293a4b5c6d7e8f90") }.uiState.value

        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", state.deviceId)
        assertNull(state.deviceIdProblem)
    }

    @Test
    fun `a session that cannot read its identity shows none and names no problem`() {
        val state = model { Result.success(null) }.uiState.value

        assertNull(state.deviceId)
        assertNull(state.deviceIdProblem)
    }

    @Test
    fun `no session shows no device ID and says why`() {
        val state =
            model { Result.failure(IllegalStateException("No entry point is configured.")) }.uiState.value

        assertNull(state.deviceId)
        assertEquals("No entry point is configured.", state.deviceIdProblem)
    }

    @Test
    fun `the screen says it is reading until the session answers`() {
        val answer = CompletableDeferred<Result<String?>>()
        val model = model { answer.await() }
        assertFalse(model.uiState.value.deviceIdAnswered)

        answer.complete(Result.success(null))

        assertTrue(model.uiState.value.deviceIdAnswered)
    }

    @Test
    fun `a recheck reads the device ID again`() {
        var answer: String? = null
        val model = model { Result.success(answer) }
        answer = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

        model.recheck()

        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", model.uiState.value.deviceId)
    }

    private fun model(readDeviceId: suspend () -> Result<String?>): SetupViewModel {
        val target = TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator)
        return SetupViewModel(
            configuration =
                DemoConfiguration(
                    "test6",
                    "com.payabli.example.app",
                    "AB:CD",
                    DemoEnvironment.SANDBOX,
                    true,
                ),
            tokenServer = target,
            tokenClient = TokenServerClient(target),
            readDeviceFacts = { facts },
            readDeviceId = readDeviceId,
            demoCustomer = DemoCustomerSetting(SampleIdentity.from("Test Device")),
            simpleCapture = SimpleCaptureSetting(),
            formSetup = PayInForms.storePaymentMethod(),
        )
    }

    private val facts =
        DeviceFacts(
            isEmulator = false,
            model = "Pixel 8",
            apiLevel = 34,
            readerSupported = true,
            hasNfcHardware = true,
            isNfcEnabled = true,
            playServicesInstalled = true,
            playStoreInstalled = true,
            packageName = "com.payabli.example.app",
            signingCertificateDigest = "AB:CD",
        )
}
