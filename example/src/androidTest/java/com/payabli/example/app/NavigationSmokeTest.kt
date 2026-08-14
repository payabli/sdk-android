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
        // configures none. The value reaches no service: the token endpoint above answers everything.
        application.container.applyTestConfiguration(TEST_ENTRY_POINT)
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
    fun aTabKeepsItsStateWhileAnotherIsVisited() {
        // The whole reason the navigation uses nested graphs with saveState and restoreState. No unit
        // test can reach it, and getting it wrong looks like nothing until someone switches tabs.
        //
        // Asserted on what the payer typed rather than on a pushed result screen. Both tabs push their
        // result only after the service accepts the payment: the saved-method destination pops itself
        // when nothing was stored, and the capture one when no transaction came back. So a test that
        // waits for a pushed screen is waiting for a live backend, which this tier does not have and
        // should not need. The graph's state either survives the trip or it does not, and typed values
        // show that with nothing mocked.
        launch()

        // The form is the second step and stays blocked until the first one passes, so the check
        // comes before anything can be filled in.
        compose.onNodeWithText("Check token endpoint").performScrollTo().performClick()
        awaitExists("Save payment method")

        fillTheForm()
        // Exists rather than displayed: filling the rest of the form scrolls this field away, and whether
        // it is in the viewport is not what is being claimed.
        compose.onNode(hasSetTextAction() and hasText(CARDHOLDER)).assertExists()

        open(TopLevelDestination.Setup)
        open(TopLevelDestination.PaymentMethod)

        // Restored, not rebuilt: a graph that lost its state would send the tab back to its first step,
        // where the form does not exist at all and the token check has to be run again.
        //
        // Asserted on the destination rather than on what was typed into it. Whether the form's fields
        // still hold their values is the form's business, and it is not uniform: they survive on two
        // handsets and not on a third, where the destination is restored all the same. Asserting the
        // field here would fail this test for something it does not cover.
        compose.onNodeWithText("Save payment method").assertExists()
        compose.onNodeWithText("Check token endpoint").assertDoesNotExist()
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

        /** Typed in, then looked for after the trip: the value is what shows the graph kept its state. */
        const val CARDHOLDER = "Test Cardholder"
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
