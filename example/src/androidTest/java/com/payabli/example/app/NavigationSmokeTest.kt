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
import com.payabli.example.app.ui.nav.PayabliDemoNavHost
import com.payabli.example.app.ui.nav.TopLevelDestination
import com.payabli.example.app.ui.theme.PayabliDemoTheme
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
 * CI runs no instrumented tests at all, and the nightly emulator job covers `:core` only, so nothing
 * here gates a merge. That is why this file is small: it covers the one behaviour a unit test cannot
 * reach, and stops. Anyone extending it is spending effort on a check no reviewer will see go red.
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
     * on one and the demo controller cannot reach a real backend from a test device.
     *
     * `applyLaunchOverride` is the app's own way of being pointed somewhere else, so this uses the
     * supported path rather than adding a seam for the test.
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

        // Each assertion names the last step of its sequence, or a label unique to the screen. A
        // step's title renders whatever its status, so this does not depend on how far the flow has
        // got; its empty state does, which is what makes the title the thing to look for.
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
        // Waited for, not asserted straight away. The click starts a submission that suspends, and
        // `assertIsDisplayed` runs once Compose is idle, which it is while that call is in flight.
        // The screen is pushed when the result arrives, so the assertion could look for it first.
        awaitText("Payment method saved")

        open(TopLevelDestination.Setup)
        open(TopLevelDestination.PaymentMethod)

        compose.onNodeWithText("Payment method saved").assertIsDisplayed()
    }

    /**
     * Fills every field the form requires, because it will not submit until they are all valid.
     *
     * By the name each field announces, which the SDK sets for a screen reader, so this finds them
     * the way someone using one would rather than by position.
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
     * Waits for a node carrying [text], failing with the wait's own message when it never arrives.
     *
     * The timeout is what makes a failure readable: without one this waits the harness out and
     * reports a stall rather than a missing screen.
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
 * A real socket rather than a stubbed client, so the check under test is the one the app runs. It
 * uses `java.net.ServerSocket` rather than `com.sun.*`, which is absent on Android.
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
