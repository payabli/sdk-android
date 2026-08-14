package com.payabli.example.app

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.example.app.demo.ui.components.SwitchRow
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row is one control that says what it switches.
 *
 * A `Switch` beside a `Text` is a separate node from its label: a screen reader lands on "on, switch" and never
 * reads what it turns on, and the label is not a target. Both are properties of the composition, so neither can
 * be shown on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class SwitchRowInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theLabelCarriesTheSwitchState() {
        showRow(checked = false)

        // By the label, which is what a reader sees and what a screen reader reads. On separate nodes this
        // finds a `Text`, and a `Text` has no state to be off.
        compose.onNodeWithText(LABEL).assertIsOff()
    }

    @Test
    fun theLabelIsPartOfWhatTogglesIt() {
        var toggled: Boolean? = null
        showRow(checked = false) { toggled = it }

        compose.onNodeWithText(LABEL).performClick()

        assertEquals(true, toggled)
    }

    @Test
    fun theRowReportsTheStateItWasGiven() {
        showRow(checked = true)

        compose.onNodeWithText(LABEL).assertIsOn()
    }

    private fun showRow(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            PayabliDemoTheme {
                SwitchRow(
                    label = LABEL,
                    checked = checked,
                    note = "What each position sends.",
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }

    private companion object {
        const val LABEL = "Send a customer number"
    }
}
