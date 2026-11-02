package com.payabli.example.app.demo.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** The variant without the card reader draws no Tap to Pay tab: three tabs without Simple Capture. */
class CardPresentTabTest {
    @Test
    fun `the bar has no Tap to Pay tab`() {
        assertEquals(
            listOf(
                TopLevelDestination.PaymentMethod,
                TopLevelDestination.Capture,
                TopLevelDestination.Setup,
            ),
            shownDestinations(simpleCaptureShown = false),
        )
    }
}
