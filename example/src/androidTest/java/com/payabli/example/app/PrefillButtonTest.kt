package com.payabli.example.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
 * The prefill button fills the form.
 *
 * It reaches the SDK's boxes through the semantics of the composition this app hosts, and that route goes
 * quiet rather than red when it stops working: a button that fills nothing looks like a button nobody pressed.
 * `SampleWalkthroughTest` presses it too, but only against a real environment, so without this an ordinary run
 * says nothing about the demo's own tool.
 *
 * A token endpoint is needed for the reason it is needed there: the form is the second step and stays locked
 * until the first one answers. `FakeTokenServer` is `NavigationSmokeTest`'s.
 *
 * Not on API 37, where Espresso's input injection is gone; green on API 36.
 */
@RunWith(AndroidJUnit4::class)
class PrefillButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var tokenServer: FakeTokenServer

    private var offeredBeforeThisRan = false

    private val container
        get() =
            (
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as PayabliDemoApplication
            ).container

    /**
     * The button is offered here rather than by the build, so this runs whatever `payabli.demo.prefill` was
     * set to. Asserting on the property instead would fail every ordinary run, and skipping on it would leave
     * a standing skip nobody can tell from a regression.
     */
    @Before
    fun pointTheAppAtATokenServerAndOfferThePrefill() {
        tokenServer = FakeTokenServer()
        offeredBeforeThisRan = container.configuration.prefillEnabled
        container.applyLaunchOverride("127.0.0.1:${tokenServer.port}")
        container.applyTestConfiguration(TEST_ENTRY_POINT, InstrumentedSession.ENVIRONMENT, prefillEnabled = true)
    }

    @After
    fun stopTheTokenServer() {
        tokenServer.close()
        // The container outlives a test, so what this turned on is turned back to what the build asked for.
        container.applyTestConfiguration(
            TEST_ENTRY_POINT,
            InstrumentedSession.ENVIRONMENT,
            prefillEnabled = offeredBeforeThisRan,
        )
    }

    @Test
    fun theButtonFillsTheBoxesTheFormDraws() {
        openTheForm()

        prefill()

        // The card as the field writes it, so a value that reached the box without going through the field's
        // own formatting fails here. And this device's own last name, which is what makes the rows a run
        // produces attributable, read off the form rather than off the identity.
        awaitExists(GROUPED_PAN)
        compose.onNodeWithText(container.sampleIdentity.lastName).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aSecondTapFillsBoxesThePayerHasSinceEdited() {
        // The case the seeding this replaced could not do: the same values twice were not a change, so the
        // second tap did nothing and a demo run had to leave the screen and come back.
        openTheForm()
        prefill()

        compose.onNodeWithText(GROUPED_PAN).performScrollTo().performTextReplacement("4242")
        prefill()

        awaitExists(GROUPED_PAN)
    }

    @Test
    fun theSheetCarriesItsOwnButtonAndFillingFromItFillsTheSheetsBoxes() {
        // The screen's button is behind the sheet and cannot be reached, so without one here a sheet demo has
        // no prefill at all.
        //
        // Only the sheet's boxes are asserted on. The form behind shows the same values, because both are
        // mounted on the one flow this screen holds and the values are the flow's: `PayabliPayInForm` says so
        // in as many words. So there is one draft, and which form the fill was aimed at is not a difference
        // this screen can show.
        openTheForm()
        compose.onNodeWithText("Open as a sheet instead").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = APPEARS_WITHIN_MILLIS) {
            compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(hasText(PREFILL) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNode(hasText(GROUPED_PAN) and hasAnyAncestor(isDialog())).assertExists()
    }

    private fun openTheForm() {
        compose.setContent {
            PayabliDemoTheme {
                PayabliDemoNavHost()
            }
        }
        // By tag, because three nav labels also appear as the title of the screen they open.
        compose.onNodeWithTag(TopLevelDestination.Capture.testTag).performClick()
        compose.onNodeWithText("Check token endpoint").performClick()
        // The form's own button, which exists only once the step is done.
        awaitExists(SUBMIT)
    }

    private fun prefill() {
        compose.onNodeWithText(PREFILL).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun awaitExists(text: String) {
        compose.waitUntil(timeoutMillis = APPEARS_WITHIN_MILLIS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        val TEST_ENTRY_POINT = InstrumentedSession.ENTRY_POINT

        const val SUBMIT = "Submit payment"

        const val PREFILL = "Prefill test data (Debug)"

        /** The test card as the field draws it, which is how a query finds what a box is holding. */
        const val GROUPED_PAN = "4111 1111 1111 1111"

        /** No network in it: the fill writes the form's state and the next frame draws it. */
        const val APPEARS_WITHIN_MILLIS = 10_000L
    }
}
