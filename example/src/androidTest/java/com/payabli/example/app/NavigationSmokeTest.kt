package com.payabli.example.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
        // The payment sequence gates its first step on a configured entry point, and a fresh checkout
        // configures none. The token endpoint above is this test's own; the entry point names no paypoint.
        application.container.applyTestConfiguration(TEST_ENTRY_POINT, InstrumentedSession.ENVIRONMENT)
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
        assertReachable("3. Transaction")

        open(TopLevelDestination.TapToPay)
        assertReachable("4. Take a payment")

        open(TopLevelDestination.Setup)
        assertReachable("Chosen because")

        open(TopLevelDestination.PaymentMethod)
        assertReachable("3. Stored method")
    }

    @Test
    fun aTabKeepsItsProgressWhileAnotherIsVisited() {
        // The whole reason the navigation uses nested graphs with saveState and restoreState. No unit
        // test can reach it, and getting it wrong looks like nothing until someone switches tabs.
        //
        // Driven on Capture, not on the tab the NavHost starts at. `switchTo` pops up to the start
        // destination without including it, so the first tab's entry is never popped and its state
        // survives whether the flags are set or not: the same test written there cannot fail.
        launch()
        open(TopLevelDestination.Capture)

        compose.onNodeWithText(SUBMIT).assertDoesNotExist()

        compose.onNodeWithText("Check token endpoint").performScrollTo().performClick()
        // Waited on the form's own button, which exists only once the step is done. The check's button is
        // not that signal: it reads "Checking…" while the request is in flight, so waiting for the old
        // label to go returns while the session is still starting and the form is not there to fill.
        awaitExists(SUBMIT)

        open(TopLevelDestination.Setup)
        open(TopLevelDestination.Capture)

        // Restored, not rebuilt. A rebuilt tab starts its sequence again: it offers the check and has no
        // form yet, and both halves are asserted because a tab caught mid-recheck shows neither.
        compose.onNodeWithText("Check token endpoint").assertDoesNotExist()
        compose.onNodeWithText(SUBMIT).assertExists()

        // Filled last, after the assertions above, so the trip is measured on a tab that has only had its
        // step completed. Filling first leaves the returning tab re-running its check, and a tab that is
        // checking shows no control either, which reads as restored when it is not.
        fillTheForm()
    }

    /**
     * Fills every field the form requires, because it will not submit until they are all valid.
     *
     * By the name each field announces to a screen reader, not by position.
     */
    private fun fillTheForm() {
        typeInto("Name on card", CARDHOLDER)
        typeInto("Card number", "4111111111111111")
        chooseAnExpiry()
        typeInto("CVV", "123")
        typeInto("Postal code", "94107")
        typeInto("First name", "Test")
        typeInto("Last name", "Cardholder")
        typeInto("Billing email", "test.cardholder@example.com")
    }

    /**
     * By the label the field carries, matched on the field itself rather than on a description of it.
     *
     * `onNodeWithContentDescription` matched nothing here: the SDK's form sets a content description only
     * for a field whose label does *not* float, and the demo configures the floating layout. This is the
     * selector `:payin`'s own instrumented tests use, and it holds in either layout.
     */
    private fun typeInto(
        field: String,
        value: String,
    ) {
        compose.onNode(hasSetTextAction() and hasText(field)).performScrollTo().performTextInput(value)
    }

    /** Brings the node into the viewport, then asserts it is there. */
    private fun assertReachable(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    /** The expiry is a picker, so it is opened and confirmed rather than typed into. */
    private fun chooseAnExpiry() {
        compose.onNodeWithContentDescription("Choose an expiry").performScrollTo().performClick()
        awaitExists("Done")
        compose.onNodeWithText("Done").performClick()
    }

    /**
     * Waits for the node to compose, without requiring it to be on screen.
     *
     * Without the timeout this waits the harness out, and a missing screen reports as a stall.
     */
    private fun awaitExists(text: String) {
        compose.waitUntil(timeoutMillis = APPEARS_WITHIN_MILLIS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        /** Generous against the demo controller's own delay, and short enough to fail rather than hang. */
        const val APPEARS_WITHIN_MILLIS = 5_000L

        /**
         * Stands in for the entry point a build supplies, which a checkout does not have.
         *
         * Shared with the other class that installs a session: see [InstrumentedSession].
         */
        const val TEST_ENTRY_POINT = InstrumentedSession.ENTRY_POINT

        /** One of the values the form needs before it will submit. Nothing asserts on it. */
        const val CARDHOLDER = "Test Cardholder"

        /** The capture form's submit button, which exists only once the token step is done. */
        const val SUBMIT = "Submit payment"
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
                        drainHttpRequest(client.getInputStream())
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
