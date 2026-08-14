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
    fun aTabKeepsItsProgressWhileAnotherIsVisited() {
        // The whole reason the navigation uses nested graphs with saveState and restoreState. No unit
        // test can reach it, and getting it wrong looks like nothing until someone switches tabs.
        //
        // Driven on Capture, not on the tab the NavHost starts at. `switchTo` pops up to the start
        // destination without including it, so the first tab's entry is never popped and its state
        // survives whether the flags are set or not: the same test written there passes with the
        // behaviour deleted, which is a test that cannot fail.
        //
        // Asserted on the token step rather than on what was typed or on a pushed result screen. The step
        // titles render at every status, so they cannot show how far the flow got; the control disappears
        // once the step is done, which can. A pushed result screen would need the service to accept a
        // payment, which this tier has no way to arrange.
        launch()
        open(TopLevelDestination.Capture)

        compose.onNodeWithText("Check token endpoint").performScrollTo().performClick()
        awaitGone("Check token endpoint")

        // Filled on the way through, which is what puts the SDK's own form in the journey. Nothing is
        // submitted: the service would have to accept it, and this tier has no service.
        fillTheForm()

        open(TopLevelDestination.Setup)
        open(TopLevelDestination.Capture)

        // Restored, not rebuilt. Rebuilt from its start destination the tab offers the check again, which
        // is what removing either flag produces.
        compose.onNodeWithText("Check token endpoint").assertDoesNotExist()
        compose.onNodeWithText("Submit payment").assertExists()
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

    /** Waits for a node carrying [text] to leave the tree, which is how a finished step reads. */
    private fun awaitGone(text: String) {
        compose.waitUntil(timeoutMillis = APPEARS_WITHIN_MILLIS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
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
