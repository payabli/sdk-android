package com.payabli.example.app.demo.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** The variant linking the card reader draws the Tap to Pay tab: four tabs without Simple Capture. */
class CardPresentTabTest {
    @Test
    fun `the bar has the Tap to Pay tab`() {
        assertEquals(
            listOf(
                TopLevelDestination.PaymentMethod,
                TopLevelDestination.Capture,
                TopLevelDestination.TapToPay,
                TopLevelDestination.Setup,
            ),
            shownDestinations(simpleCaptureShown = false),
        )
    }
}
