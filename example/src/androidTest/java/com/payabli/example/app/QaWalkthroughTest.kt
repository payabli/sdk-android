package com.payabli.example.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.demo.config.DemoEnvironment
import com.payabli.example.app.demo.ui.nav.PayabliDemoNavHost
import com.payabli.example.app.demo.ui.nav.TopLevelDestination
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import org.junit.After
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
 * is excluded by name in `example/build.gradle.kts` unless `payabli.qaWalkthrough=true`, so an ordinary run
 * neither sends a payment nor reports a skip for one it did not send.
 *
 * Driving the form is what puts the flow on the screen, so several devices run it together and each shows
 * what it is doing. What makes the resulting rows attributable is the prefill: it fills the form from the
 * device's own model, so no two devices submit the same customer.
 *
 * ```
 * adb -s <serial> reverse tcp:8787 tcp:8787
 * ANDROID_SERIAL=<serial> ./gradlew :example:connectedDebugAndroidTest \
 *   -Ppayabli.qaWalkthrough=true -Ppayabli.demo.prefill=true \
 *   -Ppayabli.demo.environment=qa -Ppayabli.demo.entryPoint=<entry>
 * ```
 *
 * Given the four `payabli.liveTest.*` values instead, it serves its own token from [LiveTokenServer] and points
 * the app there, so nothing runs beside it and no port is forwarded. That is the form CI takes; the command
 * above is the bench, where the running `example-server` is the thing being exercised.
 *
 * **The device has to be unlocked.** A phone that locked while idle is awake and still shows the keyguard, so
 * no activity reaches the foreground and every flow here fails with "No compose hierarchies found in the app",
 * which names Compose and not the lock. Dismiss the keyguard on every target before starting a run.
 */
@RunWith(AndroidJUnit4::class)
class QaWalkthroughTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private var tokenServer: LiveTokenServer? = null

    /**
     * The prefill button is what fills the form, and it is drawn only for a build that was told to offer it.
     *
     * Checked before anything is driven, because without it the failure is a missing node partway through a
     * flow that had already reached the service.
     *
     * One method rather than two, because JUnit orders `@Before` methods arbitrarily: split, the token setup
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

        serveTheTokenWhenCredentialsWerePassed()
    }

    private fun serveTheTokenWhenCredentialsWerePassed() {
        val arguments = InstrumentationRegistry.getArguments()
        val values = CREDENTIAL_ARGUMENTS.associateWith { arguments.getString("liveTest.$it") }
        val missing = values.filterValues { it == null }.keys

        // All four or none. A partial set is refused rather than left to the bench path, which asks for a
        // token at whatever address the build was compiled with: that fails at the first step and names a
        // form that never unlocked, not the argument nobody passed.
        if (missing.size == CREDENTIAL_ARGUMENTS.size) return
        if (missing.isNotEmpty()) {
            error("liveTest arguments are partly set. Missing: ${missing.sorted().joinToString()}")
        }

        val environment =
            DemoEnvironment.entries.firstOrNull { it.label.equals(values.getValue("environment"), true) }
                ?: error("liveTest.environment named no environment: ${values.getValue("environment")}")

        tokenServer =
            LiveTokenServer(
                baseUrl = environment.baseUrl,
                clientId = values.getValue("clientId")!!,
                clientSecret = values.getValue("clientSecret")!!,
            ).also { container.applyLaunchOverride("127.0.0.1:${it.port}") }

        container.applyTestConfiguration(values.getValue("entryPoint")!!, environment)
    }

    @After
    fun stopTheTokenServer() {
        val server = tokenServer ?: return
        val failure = server.servingFailure
        server.close()
        // Raised here because the app cannot: a token step that got no token leaves a form that never
        // unlocked, and every unreachable server looks like that. This says which one it was.
        if (failure != null) throw AssertionError("the token endpoint failed: ${failure.message}", failure)
    }

    @Test
    fun savingACardThePayerEntered() {
        openTheForm(TopLevelDestination.PaymentMethod, submit = SAVE)

        prefill()
        submit(SAVE)

        awaitOutcome("Payment method saved")
    }

    @Test
    fun savingABankAccountThePayerEntered() {
        openTheForm(TopLevelDestination.PaymentMethod, submit = SAVE)

        chooseTheBankAccount()
        prefill()
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
        submit(CAPTURE)

        awaitOutcome("Payment submitted")
    }

    @Test
    fun capturingABankAccountThePayerEntered() {
        openTheForm(TopLevelDestination.Capture, submit = CAPTURE)

        chooseTheBankAccount()
        prefill()
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
     * Waited for by a bank-only field. The prefill fills the method the form last reported it was on, and that
     * report arrives a frame after the tap: prefilled too early, the card values are seeded into a bank form,
     * every bank field stays empty and the form refuses itself. Three of four devices lost that race and the
     * fastest one won it, which is what a missing wait looks like.
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
        awaitExists(container.qaIdentity.lastName, COMPOSES_WITHIN_MILLIS)
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
        val CREDENTIAL_ARGUMENTS = listOf("environment", "entryPoint", "clientId", "clientSecret")

        const val SAVE = "Save payment method"
        const val CAPTURE = "Submit payment"

        /** What the screens put in front of a failure, form and step alike. */
        const val REFUSED = "✗"

        /** A real request over a real link, so this is a network timeout rather than a composition one. */
        const val ANSWERS_WITHIN_MILLIS = 30_000L

        /** No network in it: the prefill writes the form state and the next frame draws it. */
        const val COMPOSES_WITHIN_MILLIS = 5_000L
    }
}
