package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.ThrowableRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames are kept because they carry no cardholder data; messages are scrubbed because they can. */
class ThrowableRendererTest {
    @Test
    fun messageIsScrubbedAndFramesAreKept() {
        // NumberFormatException echoes its input verbatim, which is exactly how a PAN reaches a log.
        val rendered =
            ThrowableRenderer.render(
                NumberFormatException("For input string: \"${LogFixtures.DIGITS_16}\""),
            )

        assertFalse(rendered.contains(LogFixtures.DIGITS_16))
        assertTrue(rendered.startsWith("java.lang.NumberFormatException: For input string: \"[REDACTED]\""))
        assertTrue(rendered.contains("\n\tat "))
    }

    @Test
    fun blankMessageIsOmitted() {
        assertTrue(ThrowableRenderer.render(IllegalStateException()).startsWith("java.lang.IllegalStateException\n"))
    }

    @Test
    fun causeChainIsDepthCapped() {
        var throwable: Throwable = IllegalStateException("deepest-cause-marker")
        repeat(8) { throwable = IllegalStateException("wrap $it", throwable) }

        val rendered = ThrowableRenderer.render(throwable)

        assertEquals(ThrowableRenderer.MAX_CAUSE_DEPTH, rendered.split("Caused by: ").size - 1)
        assertTrue(rendered.startsWith("java.lang.IllegalStateException: wrap 7"))
        assertTrue(rendered.contains("wrap 2"))
        assertFalse(rendered.contains("wrap 1"))
        assertFalse(rendered.contains("deepest-cause-marker"))
    }

    @Test
    fun causeMessagesAreScrubbedAtEveryDepth() {
        val rendered =
            ThrowableRenderer.render(
                IllegalStateException("outer", IllegalArgumentException(LogFixtures.DIGITS_12)),
            )

        assertFalse(rendered.contains(LogFixtures.DIGITS_12))
        assertTrue(rendered.contains("Caused by: java.lang.IllegalArgumentException: [REDACTED]"))
    }
}
