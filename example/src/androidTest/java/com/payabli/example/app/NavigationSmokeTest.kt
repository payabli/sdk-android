package com.payabli.example.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.example.app.ui.nav.PayabliDemoNavHost
import com.payabli.example.app.ui.nav.TopLevelDestination
import com.payabli.example.app.ui.theme.PayabliDemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two things, and no more.
 *
 * CI runs no instrumented tests at all, and the nightly emulator job covers `:core` only, so nothing
 * here gates a merge. That is why this file is small: it covers the one behaviour a unit test cannot
 * reach, and stops. Anyone extending it is spending effort on a check no reviewer will see go red.
 *
 * Run locally with `./gradlew :example:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule
    val compose = createComposeRule()

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

        // Each assertion names an empty state or a label unique to its screen, so a destination that
        // failed to resolve cannot pass by leaving the previous screen on display.
        open(TopLevelDestination.Capture)
        compose.onNodeWithText("No payment yet").assertIsDisplayed()

        open(TopLevelDestination.TapToPay)
        compose.onNodeWithText("Nothing done yet").assertIsDisplayed()

        open(TopLevelDestination.Setup)
        compose.onNodeWithText("Chosen because").assertIsDisplayed()

        open(TopLevelDestination.PaymentMethod)
        compose.onNodeWithText("Nothing stored yet").assertIsDisplayed()
    }

    @Test
    fun aPushedScreenSurvivesLeavingItsTabAndComingBack() {
        // The whole reason the navigation uses nested graphs with saveState and restoreState. No unit
        // test can reach it, and getting it wrong looks like nothing until someone switches tabs.
        launch()

        compose.onNodeWithText("Save payment method").performClick()
        compose.onNodeWithText("Payment method saved").assertIsDisplayed()

        open(TopLevelDestination.Setup)
        open(TopLevelDestination.PaymentMethod)

        compose.onNodeWithText("Payment method saved").assertIsDisplayed()
    }
}
