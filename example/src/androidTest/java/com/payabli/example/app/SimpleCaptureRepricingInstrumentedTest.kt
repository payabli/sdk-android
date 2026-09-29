package com.payabli.example.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/**
 * The screen reprices when the form changes instrument, which is the case the method-change callback
 * exists for.
 *
 * Like the smoke tier, no automated job runs this: the per-PR emulator job runs the SDK's modules, and
 * this app's tier is local. Run it with `./gradlew :example:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SimpleCaptureRepricingInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var tokenServer: FakeTokenServer

    /**
     * A token endpoint that answers, because the form appears only once the session resolves and the
     * screen gates the form on one.
     */
    @Before
    fun pointTheAppAtATokenServer() {
        tokenServer = FakeTokenServer()
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PayabliDemoApplication
        application.container.applyLaunchOverride("127.0.0.1:${tokenServer.port}")
        application.container.applyTestConfiguration(InstrumentedSession.ENTRY_POINT, InstrumentedSession.ENVIRONMENT)
        // The switch is saved across launches, so an earlier run or a developer can leave it on.
        application.container.simpleCapture.setShown(false)
    }

    @After
    fun stopTheTokenServer() {
        tokenServer.close()
        // The setting lives on the container, which outlives a test, so a run that turned it on would
        // leave the tab showing for whatever runs next.
        (
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PayabliDemoApplication
        ).container.simpleCapture.setShown(false)
    }

    @Test
    fun switchingInstrumentSwitchesTheFeeOnTheTotal() {
        compose.setContent {
            PayabliDemoTheme {
                PayabliDemoNavHost()
            }
        }

        compose.onNodeWithTag(TopLevelDestination.Setup.testTag).performClick()
        compose.onNodeWithText(SHOW_SIMPLE_CAPTURE).performScrollTo().performClick()
        compose.onNodeWithTag(TopLevelDestination.SimpleCapture.testTag).performClick()
        awaitExists(SUBMIT)

        // The fee of the instrument the form opened on, and the total it rides inside.
        feeRow("0.30").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(TOTAL) and hasText("12.64") and !hasSetTextAction()).assertExists()

        compose.onNode(hasClickAction() and hasText(BANK_TAB)).performClick()

        // The fee moved with the instrument, and the card's figures are gone.
        feeRow("0.10").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(TOTAL) and hasText("12.44") and !hasSetTextAction()).assertExists()
        compose.onNode(hasText(FEE) and hasText("0.30")).assertDoesNotExist()
    }

    @Test
    fun theFeeSurvivesATripAwayFromTheScreen() {
        compose.setContent {
            PayabliDemoTheme {
                PayabliDemoNavHost()
            }
        }

        compose.onNodeWithTag(TopLevelDestination.Setup.testTag).performClick()
        compose.onNodeWithText(SHOW_SIMPLE_CAPTURE).performScrollTo().performClick()
        compose.onNodeWithTag(TopLevelDestination.SimpleCapture.testTag).performClick()
        awaitExists(SUBMIT)
        compose.onNode(hasClickAction() and hasText(BANK_TAB)).performClick()
        feeRow("0.10").performScrollTo().assertIsDisplayed()

        // A trip to another tab rebuilds the screen while the flow and its draft survive, which is
        // also what a rotation does.
        compose.onNodeWithTag(TopLevelDestination.Setup.testTag).performClick()
        compose.onNodeWithTag(TopLevelDestination.SimpleCapture.testTag).performClick()
        awaitExists(SUBMIT)

        feeRow("0.10").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(FEE) and hasText("0.30")).assertDoesNotExist()
    }

    /** The summary row for the fee, read back from the operation rather than typed. */
    private fun feeRow(figure: String) = compose.onNode(hasText(FEE) and hasText(figure) and !hasSetTextAction())

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
        /** Generous against the app's own startup, and short enough to fail rather than hang. */
        const val APPEARS_WITHIN_MILLIS = 5_000L

        /** The setup screen's switch, which adds the tab this test opens. */
        const val SHOW_SIMPLE_CAPTURE = "Show Simple Capture"

        /** The form's own default label, since this screen passes no labels of its own. */
        const val SUBMIT = "Submit"

        const val FEE = "Fee"

        const val TOTAL = "Total"

        /** The selector's own label for the instrument this test switches to. */
        const val BANK_TAB = "Bank account"
    }
}
