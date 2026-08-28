package com.payabli.example.app.demo.ui.nav

import com.payabli.example.app.demo.config.SimpleCaptureSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tabs the bar shows, which is the whole of what the setting decides.
 *
 * The bar filters on [TopLevelDestination.isOptional], so the arithmetic is here rather than in a Compose
 * test: four items without the setting and five with it, and the four are the capability areas this app is
 * for.
 */
class SimpleCaptureTabTest {
    @Test
    fun `the bar is four items until the setting is on`() {
        assertEquals(4, shown(simpleCaptureOn = false).size)
        assertFalse(TopLevelDestination.SimpleCapture in shown(simpleCaptureOn = false))
    }

    @Test
    fun `the setting adds the fifth`() {
        assertEquals(5, shown(simpleCaptureOn = true).size)
        assertTrue(TopLevelDestination.SimpleCapture in shown(simpleCaptureOn = true))
    }

    /** Only that one waits on a setting: every other tab is what the app is for. */
    @Test
    fun `nothing else is optional`() {
        assertEquals(
            listOf(TopLevelDestination.SimpleCapture),
            TopLevelDestination.entries.filter { it.isOptional },
        )
    }

    /** It sits between the capture it simplifies and the rest, which is where the bar draws it. */
    @Test
    fun `it follows Capture in the bar`() {
        val order = shown(simpleCaptureOn = true)
        assertEquals(
            order.indexOf(TopLevelDestination.Capture) + 1,
            order.indexOf(TopLevelDestination.SimpleCapture),
        )
    }

    @Test
    fun `the setting starts off`() {
        assertFalse(SimpleCaptureSetting().shown.value)
    }

    @Test
    fun `turning it on is what the screen reads`() {
        val setting = SimpleCaptureSetting()

        setting.setShown(true)

        assertTrue(setting.shown.value)
    }

    private fun shown(simpleCaptureOn: Boolean) = shownDestinations(simpleCaptureShown = simpleCaptureOn)
}
