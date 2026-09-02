package com.payabli.example.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.demo.ui.nav.PayabliDemoNavHost
import com.payabli.example.app.demo.ui.nav.TopLevelDestination
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import com.payabli.example.app.sdk.DemoEnvironment
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The four card-not-present flows, driven through the form this app actually shows, against a real environment.
 *
 * `NavigationSmokeTest` walks the same screens against a fake token endpoint and stops before submitting. This
 * one submits, so it needs a reachable token server and a configured paypoint, and it sends real requests. It
 * is excluded by name in `example/build.gradle.kts` unless `payabli.sampleWalkthrough=true`, so an ordinary run
 * neither sends a payment nor reports a skip for one it did not send.
 *
 * Driving the form is what puts the flow on the screen, so several devices run it together and each shows
 * what it is doing. What makes the resulting rows attributable is the prefill: it fills the form from the
 * device's own model, so no two devices submit the same customer.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :example:connectedDebugAndroidTest \
 *   -Ppayabli.sampleWalkthrough=true -Ppayabli.demo.prefill=true \
 *   -Ppayabli.demo.environment=sandbox -Ppayabli.demo.entryPoint=<entry>
 * ```
 *
 * Given the three `payabli.liveTest.*` values instead, the address, entry point and environment come from the
 * run rather than from what the build compiled in, which is how CI points the app at the server it started. A
 * token server is required either way, and holds the client credential the app never sees.
 *
 * **The device has to be unlocked.** A phone that locked while idle is awake and still shows the keyguard, so
 * no activity reaches the foreground and every flow here fails with "No compose hierarchies found in the app",
 * which names Compose and not the lock. Dismiss the keyguard on every target before starting a run.
 */
@RunWith(AndroidJUnit4::class)
class SampleWalkthroughTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer

    /**
     * The prefill button is what fills the form, and it is drawn only for a build that was told to offer it.
     *
     * Checked before anything is driven, because without it the failure is a missing node partway through a
     * flow that had already reached the service.
     *
     * One method rather than two, because JUnit orders `@Before` methods arbitrarily: split, the address setup
     * ran first on this runner and failed on a `container` the other half had not assigned yet.
     */
    @Before
    fun prepareTheApp() {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as PayabliDemoApplication
        container = application.container

        assertTrue(
            "this build offers no prefill button: install it with -Ppayabli.demo.prefill=true",
            container.configuration.prefillEnabled,
        )

        pointTheAppAtTheTokenServerWhenOneWasNamed()
    }

    /**
     * Points the app at the token server the run named, if it named one.
     *
     * What arrives is an address, an entry point and an environment. No client credential does, and none is
     * needed here: the token server holds it and the app asks for a token, which is the arrangement an
     * integrator's backend implements and the boundary the SDK is built on.
     */
    private fun pointTheAppAtTheTokenServerWhenOneWasNamed() {
        val arguments = InstrumentationRegistry.getArguments()
        // Trimmed, and blank read as absent, as the build resolves them: an argument passed as an empty
        // string counts as present otherwise, clears the guard below, and points the app at an address that
        // is a scheme and a path. The build no longer forwards a blank, but an instrumentation argument does
        // not have to come from the build.
        val values =
            LIVE_ARGUMENTS.associateWith { arguments.getString("liveTest.$it")?.trim()?.ifEmpty { null } }
        val missing = values.filterValues { it == null }.keys

        // All three or none. A partial set is refused rather than left to the compiled-in address, which is
        // whatever the build carried: that fails at the first step and names a form that never unlocked,
        // rather than the argument nobody passed.
        if (missing.size == LIVE_ARGUMENTS.size) return
        if (missing.isNotEmpty()) {
            error("liveTest arguments are partly set. Missing: ${missing.sorted().joinToString()}")
        }

        // Narrowed once, so what follows reads as the settled values it is rather than as three more places
        // nullability might arise.
        val settings = values.mapValues { (name, value) -> requireNotNull(value) { "liveTest.$name" } }
        val environment =
            DemoEnvironment.named(settings.getValue("environment"))
                ?: error("liveTest.environment named no environment: ${settings.getValue("environment")}")

        container.applyLaunchOverride(settings.getValue("tokenHost"))
        container.applyTestConfiguration(settings.getValue("entryPoint"), environment)
    }

    @Test
    fun savingACardThePayerEntered() {
        openTheForm(TopLevelDestination.PaymentMethod, submit = SAVE)

        prefill()
        pickTheExpiry()
        submit(SAVE)

        awaitOutcome("Payment method saved")
    }

    @Test
    fun savingABankAccountThePayerEntered() {
        openTheForm(TopLevelDestination.PaymentMethod, submit = SAVE)

        chooseTheBankAccount()
        prefill()
        pickTheAccountType()
        submit(SAVE)

        awaitOutcome("Payment method saved")
    }

    @Test
    fun capturingACardThePayerEntered() {
        openTheForm(TopLevelDestination.Capture, submit = CAPTURE)

        // The figure the request carries, beside a form whose own summary reads back an amount and a fee and
        // never their sum. A payer sees what leaves the account or the screen is lying by omission, and seeing
        // it after the button that spends it is the same omission: the form's last child is its submit button,
        // so a total under the form is a total a payer reaches only by scrolling past the control it qualifies.
        val total = topOf("Total")
        val submit = topOf(CAPTURE)
        assertTrue("Total sits at $total, below the submit button at $submit", total < submit)
        compose.onNodeWithText("Total").performScrollTo().assertIsDisplayed()

        prefill()
        pickTheExpiry()
        submit(CAPTURE)

        awaitOutcome("Payment submitted")
    }

    /**
     * Taking a payment and then reversing it, through the control the capture screen offers for it.
     *
     * The walk goes out to the transaction screen and back, because that is the route a payer takes: the
     * control lives on the step that describes the result, and the result screen is pushed on top of it.
     *
     * Reversing needs a permission the taking credential does not imply, so a refusal here reads as that
     * rather than as a broken control, and `awaitOutcome` prints what the service said.
     */
    @Test
    fun capturingACardAndThenVoidingIt() {
        openTheForm(TopLevelDestination.Capture, submit = CAPTURE)

        prefill()
        pickTheExpiry()
        submit(CAPTURE)
        awaitOutcome("Payment submitted")

        // Back to the step that describes the transaction, which is where the control is.
        // Scrolled to first: the transaction screen lists a summary and the whole response above it, so Done
        // is off the viewport and a click at its coordinates lands on nothing.
        compose.onNodeWithText("Done").performScrollTo().performClick()
        awaitExists(VOID, COMPOSES_WITHIN_MILLIS)

        compose.onNodeWithText(VOID).performClick()
        // Substring, unlike the outcomes above: those are headings and a node of their own, while this is the
        // first line of the result card's text, which carries the code and the transaction after it.
        awaitOutcomeContaining(VOIDED)

        // Offered once. The transaction is reversed, so the control goes rather than sitting there inert.
        compose.waitUntil(COMPOSES_WITHIN_MILLIS) { nodesWith(VOID).isEmpty() }
    }

    @Test
    fun capturingABankAccountThePayerEntered() {
        openTheForm(TopLevelDestination.Capture, submit = CAPTURE)

        chooseTheBankAccount()
        prefill()
        pickTheAccountType()
        submit(CAPTURE)

        awaitOutcome("Payment submitted")
    }

    /**
     * Up to the form, which is the second step and stays blocked until the token endpoint has answered.
     *
     * Waits for the submit button rather than for the check's own text: that is the node the rest of the walk
     * needs, and a check that answered while the form stayed blocked would otherwise pass this.
     */
    private fun openTheForm(
        destination: TopLevelDestination,
        submit: String,
    ) {
        compose.setContent {
            PayabliDemoTheme {
                PayabliDemoNavHost()
            }
        }

        // By tag, because three nav labels also appear as the title of the screen they open.
        compose.onNodeWithTag(destination.testTag).performClick()
        compose.onNodeWithText("Check token endpoint").performClick()
        awaitExists(submit, ANSWERS_WITHIN_MILLIS)
    }

    /**
     * The instrument the form is on, which the prefill then fills.
     *
     * Waited for by a bank-only field, because the prefill fills the boxes that are on screen: run before the
     * bank form has composed, it finds the card's boxes and the bank fields stay empty.
     */
    private fun chooseTheBankAccount() {
        compose.onNodeWithText("Bank account").performScrollTo().performClick()
        // By the section's own title rather than by a field: a filled box floats its label and drops the
        // description a field name matches, and a box below the fold is not composed at all.
        awaitExists("ACH Information", COMPOSES_WITHIN_MILLIS)
    }

    private fun prefill() {
        compose.onNodeWithText("Prefill test data (Debug)").performScrollTo().performClick()
        // This device's own label, in a field, which is what makes the row it produces attributable. Read off
        // the form rather than off the identity, so a prefill that reached nothing fails here instead of at the
        // service. Not by field name: a filled box floats its label and drops the description a name matches.
        awaitExists(container.sampleIdentity.lastName, COMPOSES_WITHIN_MILLIS)
    }

    /**
     * The card expiry, which the prefill does not fill.
     *
     * It is chosen from a dialog and there is no text to set on it, so a payer picks it and so does this. The
     * dialog opens on a month that has not passed, which is why confirming it without choosing a row is enough.
     */
    private fun pickTheExpiry() {
        compose.onNodeWithContentDescription("Choose an expiry").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        // The dialog's own title, gone once it closes. Waiting on the field instead would need the month it
        // defaulted to, which is today's and belongs to the device rather than to this test.
        compose.waitUntil(COMPOSES_WITHIN_MILLIS) { nodesWith("Choose an expiry").isEmpty() }
    }

    /** The account type, which the prefill does not fill either: a menu rather than a box. */
    private fun pickTheAccountType() {
        compose.onNodeWithText("Account type").performScrollTo().performClick()
        compose.onNodeWithText("Checking").performClick()
        awaitExists("Checking", COMPOSES_WITHIN_MILLIS)
    }

    /** Scrolled to first: the button sits below the fold, so a bare click asserts against nothing. */
    private fun submit(button: String) {
        compose.onNodeWithText(button).performScrollTo().performClick()
    }

    /** Where a node sits down the scrolling column, which is how two of them are put in order. */
    private fun topOf(text: String): Float =
        compose
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .positionInRoot
            .y

    /**
     * Waits for the outcome and, if it is a refusal, fails naming what the screen said.
     *
     * Without this the failure is a bare "condition still not satisfied", which is the same message for a
     * declined payment, an unreachable service and a button that moved. The screen already renders the reason.
     *
     * The mark is the same whether the form refused the values or the service refused the request, so the
     * message quotes the screen and names neither.
     */
    private fun awaitOutcome(success: String) {
        compose.waitUntil(ANSWERS_WITHIN_MILLIS) {
            nodesWith(success).isNotEmpty() || refusals().isNotEmpty()
        }

        val refused = refusals()
        assertTrue("it was refused: ${refused.joinToString(" | ")}", refused.isEmpty())
        compose.onNodeWithText(success).assertIsDisplayed()
    }

    /**
     * The same wait as [awaitOutcome] for a result that is part of a longer line rather than a node of its own.
     *
     * Kept beside it rather than folded in: the exact match is what makes the two outcome headings assertable
     * as the whole of what a node says, and widening those would let a heading match a card that quotes it.
     */
    private fun awaitOutcomeContaining(success: String) {
        compose.waitUntil(ANSWERS_WITHIN_MILLIS) {
            compose.onAllNodesWithText(success, substring = true).fetchSemanticsNodes().isNotEmpty() ||
                refusals().isNotEmpty()
        }

        val refused = refusals()
        assertTrue("it was refused: ${refused.joinToString(" | ")}", refused.isEmpty())
    }

    /** Whatever the screen is showing under the mark it uses for a failure. */
    private fun refusals(): List<String> =
        compose
            .onAllNodesWithText(REFUSED, substring = true)
            .fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") }

    private fun awaitExists(
        text: String,
        timeoutMillis: Long,
    ) {
        compose.waitUntil(timeoutMillis) { nodesWith(text).isNotEmpty() }
    }

    private fun nodesWith(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes()

    private companion object {
        val LIVE_ARGUMENTS = listOf("environment", "entryPoint", "tokenHost")

        const val SAVE = "Save payment method"
        const val CAPTURE = "Submit payment"
        const val VOID = "Void this transaction"

        /** What the capture step shows once the service has reversed the transaction. */
        const val VOIDED = "✓ Voided"

        /** What the screens put in front of a failure, form and step alike. */
        const val REFUSED = "✗"

        /** A real request over a real link, so this is a network timeout rather than a composition one. */
        const val ANSWERS_WITHIN_MILLIS = 30_000L

        /** No network in it: the prefill writes the form state and the next frame draws it. */
        const val COMPOSES_WITHIN_MILLIS = 5_000L
    }
}
