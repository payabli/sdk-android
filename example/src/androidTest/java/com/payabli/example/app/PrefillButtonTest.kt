package com.payabli.example.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun everyBoxTheCardFormDrawsIsFilled() {
        // Every box rather than a sample of them. The fill matches each field by the label it is drawn with
        // and skips a field it cannot find, so a mapping that loses an entry fills one box fewer and says
        // nothing. Asserting two of them leaves the rest free to stop working.
        openTheForm()

        prefill()

        val identity = container.sampleIdentity
        // The card as the field draws it. The box holds the digits and the grouping is drawn over them, so
        // this is what says the value went through the field's own formatting rather than around it.
        awaitExists(GROUPED_PAN)
        assertFilledWith("Card number", "4111111111111111")
        assertFilledWith("Name on card", identity.holderName)
        assertFilledWith("Postal code", "22039")
        assertFilledWith("First name", identity.firstName)
        assertFilledWith("Last name", identity.lastName)
        assertFilledWith("Billing email", identity.billingEmail)
        // Obscured as it is typed, so what it holds is not readable off the screen and only that it holds
        // something can be asserted.
        assertFilled("CVV")
    }

    @Test
    fun everyBoxTheBankFormDrawsIsFilled() {
        // The other instrument. Its three own fields are filled by the same mapping and no card test reaches
        // them, so without this half of the mapping is unasserted.
        openTheForm()
        chooseTheBankAccount()

        prefill()

        val identity = container.sampleIdentity
        assertFilledWith("Account holder", identity.holderName)
        assertFilledWith("Routing number", "121000248")
        assertFilledWith("First name", identity.firstName)
        assertFilledWith("Last name", identity.lastName)
        assertFilledWith("Billing email", identity.billingEmail)
        assertFilled("Account number")
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

    /**
     * The bank tab, waited for by the section it draws.
     *
     * By the section title rather than by a field: the fill reads the boxes that are on screen, so running it
     * before the bank form has composed finds the card's.
     */
    private fun chooseTheBankAccount() {
        compose.onNodeWithText("Bank account").performScrollTo().performClick()
        awaitExists("ACH Information")
    }

    /** What a box holds, read off the field rather than off the screen, so an obscured one still answers. */
    private fun typedInto(label: String): String {
        val node = compose.onNode(hasSetTextAction() and hasText(label)).fetchSemanticsNode()
        // Two properties, because which one carries a text field's value has changed across Compose versions
        // and a null read here would pass as an empty box.
        return node.config.getOrNull(SemanticsProperties.InputText)?.text
            ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: error("$label carries neither InputText nor EditableText, so nothing here reads its value")
    }

    private fun assertFilled(label: String) = assertTrue("the prefill left $label empty", typedInto(label).isNotBlank())

    private fun assertFilledWith(
        label: String,
        value: String,
    ) = assertEquals("the prefill put the wrong value in $label", value, typedInto(label))

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
