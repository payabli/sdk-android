package com.payabli.example.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.demo.ui.nav.PayabliDemoNavHost
import com.payabli.example.app.demo.ui.nav.TopLevelDestination
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Two things, and no more.
 *
 * No job runs these: CI has no emulator, and the nightly covers `:core` only. Nothing added here
 * will be seen to go red.
 *
 * Run locally with `./gradlew :example:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var tokenServer: FakeTokenServer

    /**
     * A token endpoint that answers, because the first step of each payment sequence gates the rest
     * on one.
     *
     * `applyLaunchOverride` is the app's own way of being pointed somewhere else, so no seam is
     * added for the test.
     */
    @Before
    fun pointTheAppAtATokenServer() {
        tokenServer = FakeTokenServer()
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PayabliDemoApplication
        application.container.applyLaunchOverride("127.0.0.1:${tokenServer.port}")
    }

    @After
    fun stopTheTokenServer() = tokenServer.close()

    private fun launch() {
        compose.setContent {
            PayabliDemoTheme {
                PayabliDemoNavHost()
            }
        }
    }

    /** By tag, because three nav labels also appear as the title of the screen they open. */
    private fun open(destination: TopLevelDestination) {
        compose.onNodeWithTag(destination.testTag).performClick()
    }

    @Test
    fun everyCapabilityIsReachable() {
        launch()

        // By step title, which renders whatever the status. An empty state does not, so it would
        // pin how far the flow had got.
        open(TopLevelDestination.Capture)
        compose.onNodeWithText("3. Transaction").assertIsDisplayed()

        open(TopLevelDestination.TapToPay)
        compose.onNodeWithText("4. Take a payment").assertIsDisplayed()

        open(TopLevelDestination.Setup)
        compose.onNodeWithText("Chosen because").assertIsDisplayed()

        open(TopLevelDestination.PaymentMethod)
        compose.onNodeWithText("3. Stored method").assertIsDisplayed()
    }

    @Test
    fun aPushedScreenSurvivesLeavingItsTabAndComingBack() {
        // The whole reason the navigation uses nested graphs with saveState and restoreState. No unit
        // test can reach it, and getting it wrong looks like nothing until someone switches tabs.
        launch()

        // The form is the second step and stays blocked until the first one passes, so the check
        // comes before anything can be filled in.
        compose.onNodeWithText("Check token endpoint").performClick()
        awaitExists("Save payment method")

        fillTheForm()

        // Scrolled to first. The submit button sits below the fold, so the node composes without
        // being on screen and a bare click asserts against nothing.
        compose.onNodeWithText("Save payment method").performScrollTo().performClick()
        // Waited for. Compose is idle while the submission suspends, so a straight assertion runs
        // before the result screen is pushed.
        awaitText("Payment method saved")

        open(TopLevelDestination.Setup)
        open(TopLevelDestination.PaymentMethod)

        compose.onNodeWithText("Payment method saved").assertIsDisplayed()
    }

    /**
     * Fills every field the form requires, because it will not submit until they are all valid.
     *
     * By the name each field announces to a screen reader, not by position.
     */
    private fun fillTheForm() {
        typeInto("Name on card", "Test Cardholder")
        typeInto("Card number", "4111111111111111")
        chooseAnExpiry()
        typeInto("CVV", "123")
        typeInto("Postal code", "94107")
        typeInto("First name", "Test")
        typeInto("Last name", "Cardholder")
        typeInto("Billing email", "test.cardholder@example.com")
    }

    private fun typeInto(
        field: String,
        value: String,
    ) {
        compose.onNodeWithContentDescription(field).performScrollTo().performTextInput(value)
    }

    /** The expiry is a picker, so it is opened and confirmed rather than typed into. */
    private fun chooseAnExpiry() {
        compose.onNodeWithContentDescription("Choose an expiry").performScrollTo().performClick()
        awaitExists("Done")
        compose.onNodeWithText("Done").performClick()
    }

    /**
     * Waits for a node carrying [text].
     *
     * Without the timeout this waits the harness out, and a missing screen reports as a stall.
     */
    private fun awaitText(text: String) {
        awaitExists(text)
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    /** Waits for the node to compose, without requiring it to be on screen. */
    private fun awaitExists(text: String) {
        compose.waitUntil(timeoutMillis = APPEARS_WITHIN_MILLIS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** Generous against the demo controller's own delay, and short enough to fail rather than hang. */
        const val APPEARS_WITHIN_MILLIS = 5_000L
    }
}

/**
 * Answers the one route the token step calls, with the one field it reads.
 *
 * A real socket, so the app runs its own check. `java.net.ServerSocket`, since `com.sun.*` is
 * absent on Android.
 */
private class FakeTokenServer : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().use { client ->
                        client.getInputStream().bufferedReader().readLine()
                        client.getOutputStream().write(RESPONSE.toByteArray())
                        client.getOutputStream().flush()
                    }
                }
            }
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val BODY = """{"accessToken":"not-a-real-token"}"""
        val RESPONSE =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${BODY.length}\r\n" +
                "Connection: close\r\n\r\n" +
                BODY
    }
}
