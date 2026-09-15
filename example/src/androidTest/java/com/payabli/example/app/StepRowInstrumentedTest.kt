package com.payabli.example.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.example.app.demo.flow.FlowStep
import com.payabli.example.app.demo.flow.StepStatus
import com.payabli.example.app.demo.ui.components.StepRow
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a finished step still offers its controls.
 *
 * A step that has been done keeps its status and drops its content, which is right for checking the device
 * and activating it: neither is done twice. Taking a payment is done again, and a terminal that has taken one
 * has to take the next without the screen being left and returned to.
 *
 * Only a composition can show this. The status is what the pure sequence decides and it is already covered on
 * the JVM; what is covered here is what the row does with that status, which is where marking the charge step
 * done removed the amount field and the button that uses it.
 */
@RunWith(AndroidJUnit4::class)
class StepRowInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRepeatableStepKeepsItsControlsOnceDone() {
        showRow(StepStatus.Done, repeatable = true)

        compose.onNodeWithText(CONTENT).assertIsDisplayed()
    }

    @Test
    fun aStepThatIsNotRepeatableDropsThemOnceDone() {
        // The other three steps, and the reason the flag is opt-in rather than the default.
        showRow(StepStatus.Done, repeatable = false)

        compose.onNodeWithText(CONTENT).assertDoesNotExist()
    }

    @Test
    fun aRepeatableStepStillOffersItsControlsBeforeItIsDone() {
        // Done is the case that changed, so the case that did not is asserted beside it: a step nobody has
        // finished yet has to keep behaving as it always did.
        showRow(StepStatus.Current, repeatable = true)

        compose.onNodeWithText(CONTENT).assertIsDisplayed()
    }

    private fun showRow(
        status: StepStatus,
        repeatable: Boolean,
    ) {
        compose.setContent {
            PayabliDemoTheme {
                StepRow(
                    index = 4,
                    step = FlowStep(title = TITLE, detail = "The reader is already up.", status = status),
                    repeatable = repeatable,
                ) {
                    Text(CONTENT)
                }
            }
        }
    }

    private companion object {
        const val TITLE = "Take a payment"
        const val CONTENT = "Amount and the button that charges it"
    }
}
