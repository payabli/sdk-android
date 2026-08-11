package com.payabli.sdk.payin.ui

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping a payer sees, and where the cursor lands inside it.
 *
 * `VisualTransformation` is a Compose type but neither the grouping nor the offset mapping needs a
 * composition, so both are checkable here. The mapping is where the arithmetic is: an offset off by
 * one puts the next typed digit on the wrong side of a space.
 */
class CardNumberSpacingTest {
    private fun spaced(digits: String) = CardNumberSpacing.filter(AnnotatedString(digits))

    @Test
    fun `digits are grouped in fours`() {
        assertEquals("4111 1111 1111 1111", spaced("4111111111111111").text.text)
        assertEquals("3782 8224 6310 005", spaced("378282246310005").text.text)
    }

    @Test
    fun `a group boundary does not leave a trailing space`() {
        assertEquals("4111", spaced("4111").text.text)
        assertEquals("4111 1", spaced("41111").text.text)
    }

    @Test
    fun `an empty value transforms to an empty value`() {
        val result = spaced("")
        assertEquals("", result.text.text)
        assertEquals(0, result.offsetMapping.originalToTransformed(0))
        assertEquals(0, result.offsetMapping.transformedToOriginal(0))
    }

    @Test
    fun `the caret maps to the same place it would sit if the spaces were typed`() {
        // The transformed offset for a caret after n digits is n plus the spaces that precede it.
        val digits = "4111111111111111"
        val mapping = spaced(digits).offsetMapping
        (0..digits.length).forEach { offset ->
            val spacesBefore = if (offset == 0) 0 else (offset - 1) / 4
            assertEquals("after $offset digits", offset + spacesBefore, mapping.originalToTransformed(offset))
        }
    }

    @Test
    fun `every caret position in the displayed text maps back into the digits`() {
        val digits = "4111111111111111"
        val result = spaced(digits)
        (0..result.text.text.length).forEach { offset ->
            val original = result.offsetMapping.transformedToOriginal(offset)
            assertTrue("$offset mapped outside the value, to $original", original in 0..digits.length)
        }
    }

    @Test
    fun `the two directions agree at every caret position`() {
        // The property a wrong mapping breaks: putting the caret after a digit and reading it back
        // has to name that same digit, at every group boundary in a full-length number.
        val digits = "4".repeat(19)
        val mapping = spaced(digits).offsetMapping
        (0..digits.length).forEach { offset ->
            assertEquals(
                "round trip at $offset",
                offset,
                mapping.transformedToOriginal(mapping.originalToTransformed(offset)),
            )
        }
    }

    @Test
    fun `the boundaries either side of the first space behave`() {
        val mapping = spaced("41111").offsetMapping
        assertEquals("before the space", 4, mapping.originalToTransformed(4))
        assertEquals("after the space", 6, mapping.originalToTransformed(5))
        assertEquals(4, mapping.transformedToOriginal(4))
        assertEquals("the space itself belongs to the digit before it", 4, mapping.transformedToOriginal(5))
    }
}
