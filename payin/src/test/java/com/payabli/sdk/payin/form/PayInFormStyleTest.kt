package com.payabli.sdk.payin.form

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.TextFieldColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The component takes its appearance from the host's theme and names none of its own.
 *
 * Resolving under two unrelated themes has to produce two different styles. A hard-coded color
 * renders correctly under the theme it was written against, so a screenshot cannot catch it and this
 * can.
 */
class PayInFormStyleTest {
    private val light =
        PayInThemeRoles(
            onSurface = Color(0xFF1A1C1E),
            onSurfaceVariant = Color(0xFF43474E),
            error = Color(0xFFBA1A1A),
            secondaryContainer = Color(0xFFD7E3F8),
            onSecondaryContainer = Color(0xFF101C2B),
            titleType = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
            subtitleType = TextStyle(fontSize = 14.sp),
            sectionTitleType = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            labelType = TextStyle(fontSize = 12.sp),
            supportingType = TextStyle(fontSize = 12.sp),
            fieldShape = RoundedCornerShape(4.dp),
        )

    /** Unlike [light] in every role, so no value can match across the two by coincidence. */
    private val other =
        PayInThemeRoles(
            onSurface = Color(0xFFE3E2E6),
            onSurfaceVariant = Color(0xFFC3C7CF),
            error = Color(0xFFFFB4AB),
            secondaryContainer = Color(0xFF3B4858),
            onSecondaryContainer = Color(0xFFD7E3F8),
            titleType = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black),
            subtitleType = TextStyle(fontSize = 18.sp),
            sectionTitleType = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            labelType = TextStyle(fontSize = 9.sp),
            supportingType = TextStyle(fontSize = 10.sp),
            fieldShape = CutCornerShape(12.dp),
        )

    @Test
    fun `a brand mark box that cannot be drawn is refused where it was written`() {
        // Modifier.size refuses each of these, one composition later and naming the modifier rather than the
        // value. Unspecified is NaN, and NaN >= 0.dp is false, so it fails the same check as a negative.
        listOf(
            DpSize(Dp.Unspecified, Dp.Unspecified),
            DpSize(Dp.Infinity, 20.dp),
            DpSize((-1).dp, 20.dp),
            DpSize(20.dp, (-1).dp),
        ).forEach { size ->
            val refusal = runCatching { resolvePayInFormStyle(light).copy(brandMark = size) }.exceptionOrNull()
            assertTrue("$size was accepted", refusal is IllegalArgumentException)
        }
    }

    @Test
    fun `a brand mark box of zero is allowed, as a gap of zero is`() {
        resolvePayInFormStyle(light).copy(brandMark = DpSize(0.dp, 0.dp))
    }

    @Test
    fun `a gap that cannot be laid out is refused where it was written`() {
        listOf(Dp.Unspecified, Dp.Infinity, (-1).dp).forEach { gap ->
            val refusal = runCatching { PayInFormSpacing(content = gap) }.exceptionOrNull()
            assertTrue("$gap was accepted", refusal is IllegalArgumentException)
        }
    }

    @Test
    fun `every color in the style follows the theme it was resolved under`() {
        // Color by color. Comparing whole TextStyle objects passes as soon as any part of one
        // differs, which a hard-coded color survives on the strength of the font size beside it.
        val a = resolvePayInFormStyle(light)
        val b = resolvePayInFormStyle(other)

        assertNotEquals("title", a.title.color, b.title.color)
        assertNotEquals("subtitle", a.subtitle.color, b.subtitle.color)
        assertNotEquals("sectionTitle", a.sectionTitle.color, b.sectionTitle.color)
        assertNotEquals("label", a.label.color, b.label.color)
        assertNotEquals("supporting", a.supporting.color, b.supporting.color)
        assertNotEquals("error", a.error.color, b.error.color)
        assertNotEquals("selectedContainer", a.selectedContainer, b.selectedContainer)
        assertNotEquals("selectedContent", a.selectedContent, b.selectedContent)
    }

    @Test
    fun `every type size in the style follows the theme it was resolved under`() {
        val a = resolvePayInFormStyle(light)
        val b = resolvePayInFormStyle(other)

        assertNotEquals("title", a.title.fontSize, b.title.fontSize)
        assertNotEquals("subtitle", a.subtitle.fontSize, b.subtitle.fontSize)
        assertNotEquals("sectionTitle", a.sectionTitle.fontSize, b.sectionTitle.fontSize)
        assertNotEquals("label", a.label.fontSize, b.label.fontSize)
        assertNotEquals("supporting", a.supporting.fontSize, b.supporting.fontSize)
        assertNotEquals("error", a.error.fontSize, b.error.fontSize)
    }

    @Test
    fun `the field shape follows the theme too`() {
        assertNotEquals(resolvePayInFormStyle(light).fieldShape, resolvePayInFormStyle(other).fieldShape)
    }

    @Test
    fun `text colors come from the role each one belongs to`() {
        val style = resolvePayInFormStyle(light)

        assertEquals(light.onSurface, style.title.color)
        assertEquals(light.onSurface, style.sectionTitle.color)
        assertEquals(light.onSurfaceVariant, style.subtitle.color)
        assertEquals(light.onSurfaceVariant, style.label.color)
        assertEquals(light.onSurfaceVariant, style.supporting.color)
        assertEquals(light.error, style.error.color)
        assertEquals(light.secondaryContainer, style.selectedContainer)
        assertEquals(light.onSecondaryContainer, style.selectedContent)
    }

    @Test
    fun `type comes from the role, and the color does not replace the rest of it`() {
        // copy(color = ...) keeps the host's size, weight and family.
        val style = resolvePayInFormStyle(light)
        assertEquals(light.titleType.fontSize, style.title.fontSize)
        assertEquals(light.titleType.fontWeight, style.title.fontWeight)
        assertEquals(light.sectionTitleType.fontWeight, style.sectionTitle.fontWeight)
    }

    @Test
    fun `the error style is the supporting type in the error color`() {
        // One role decides the size of both, so a host that enlarges its supporting text enlarges the
        // message under a field with it.
        val style = resolvePayInFormStyle(light)
        assertEquals(light.supportingType.fontSize, style.error.fontSize)
        assertEquals(light.error, style.error.color)
    }

    // --- overrides ---

    @Test
    fun `an override replaces one value and leaves the rest following the theme`() {
        // The whole reason overrides are per property. Changing one color must not cost the caller
        // the other six.
        val loud = TextStyle(fontSize = 40.sp, color = Color(0xFF00FF00))
        val style = resolvePayInFormStyle(light, PayInFormStyleOverrides(title = loud))

        assertEquals(loud, style.title)
        assertEquals(resolvePayInFormStyle(light).sectionTitle, style.sectionTitle)
        assertEquals(resolvePayInFormStyle(light).label, style.label)
        assertEquals(resolvePayInFormStyle(light).error, style.error)
    }

    @Test
    fun `no override leaves nothing overridden`() {
        assertEquals(resolvePayInFormStyle(light), resolvePayInFormStyle(light, PayInFormStyleOverrides()))
    }

    @Test
    fun `every overridable value can actually be overridden`() {
        // A field added to the style and forgotten in the overrides would be a knob the API advertises
        // and does not have.
        val shape = CutCornerShape(3.dp)
        val spacing = PayInFormSpacing(content = 1.dp)
        val text = TextStyle(fontSize = 41.sp)
        val style =
            resolvePayInFormStyle(
                light,
                PayInFormStyleOverrides(
                    title = text,
                    subtitle = text,
                    sectionTitle = text,
                    label = text,
                    supporting = text,
                    error = text,
                    fieldShape = shape,
                    spacing = spacing,
                    selectedContainer = Color(0xFF00FF00),
                    selectedContent = Color(0xFFFF00FF),
                    brandMark = DpSize(7.dp, 3.dp),
                ),
            )

        listOf(style.title, style.subtitle, style.sectionTitle, style.label, style.supporting, style.error)
            .forEach { assertEquals(text, it) }
        assertEquals(shape, style.fieldShape)
        assertEquals(spacing, style.spacing)
        assertEquals(Color(0xFF00FF00), style.selectedContainer)
        assertEquals(Color(0xFFFF00FF), style.selectedContent)
        assertEquals(DpSize(7.dp, 3.dp), style.brandMark)
    }

    @Test
    fun `field colors are the host's Material defaults until a caller supplies their own`() {
        // null means Material's own, which already follow the host theme.
        assertNull(resolvePayInFormStyle(light).fieldColors)
    }

    @Test
    fun `a caller's field colors reach the style`() {
        // The one override the completeness test above cannot supply, because a TextFieldColors is
        // ordinarily built by a composable. Without this, dropping `fieldColors = overrides.fieldColors`
        // from the resolver leaves every other assertion in this file green.
        val theirs = fieldColors()
        assertSame(theirs, resolvePayInFormStyle(light, PayInFormStyleOverrides(fieldColors = theirs)).fieldColors)
    }

    /** A `TextFieldColors` with every role the same, which is all this needs to trace one reference. */
    private fun fieldColors(): TextFieldColors {
        val any = Color(0xFF00FF00)
        val brush = SolidColor(any)
        return TextFieldColors(
            focusedTextColor = any,
            unfocusedTextColor = any,
            disabledTextColor = any,
            errorTextColor = any,
            focusedContainerColor = any,
            unfocusedContainerColor = any,
            disabledContainerColor = any,
            errorContainerColor = any,
            cursorColor = any,
            errorCursorColor = any,
            textSelectionColors = TextSelectionColors(any, any),
            focusedIndicatorColor = any,
            unfocusedIndicatorColor = any,
            disabledIndicatorColor = any,
            errorIndicatorColor = any,
            focusedLeadingIconColor = any,
            unfocusedLeadingIconColor = any,
            disabledLeadingIconColor = any,
            errorLeadingIconColor = any,
            focusedTrailingIconColor = any,
            unfocusedTrailingIconColor = any,
            disabledTrailingIconColor = any,
            errorTrailingIconColor = any,
            focusedLabelColor = any,
            unfocusedLabelColor = any,
            disabledLabelColor = any,
            errorLabelColor = any,
            focusedPlaceholderColor = any,
            unfocusedPlaceholderColor = any,
            disabledPlaceholderColor = any,
            errorPlaceholderColor = any,
            focusedSupportingTextColor = any,
            unfocusedSupportingTextColor = any,
            disabledSupportingTextColor = any,
            errorSupportingTextColor = any,
            focusedPrefixColor = any,
            unfocusedPrefixColor = any,
            disabledPrefixColor = any,
            errorPrefixColor = any,
            focusedSuffixColor = any,
            unfocusedSuffixColor = any,
            disabledSuffixColor = any,
            errorSuffixColor = any,
        ).also { require(brush.value == any) { "the fixture built nothing" } }
    }

    // --- spacing, the one group with no theme role ---

    @Test
    fun `spacing has defaults, because Material has no spacing scale to read`() {
        val spacing = resolvePayInFormStyle(light).spacing
        assertTrue(spacing.content > 0.dp)
        assertTrue(spacing.fieldGroup > 0.dp)
        assertTrue(spacing.section > 0.dp)
    }

    @Test
    fun `a negative gap is refused where it is written, not where it draws`() {
        // A negative Dp overlaps two fields on screen and says nothing about why.
        listOf<() -> PayInFormSpacing>(
            { PayInFormSpacing(content = (-1).dp) },
            { PayInFormSpacing(header = (-1).dp) },
            { PayInFormSpacing(fieldGroup = (-1).dp) },
            { PayInFormSpacing(pairedField = (-1).dp) },
            { PayInFormSpacing(label = (-1).dp) },
            { PayInFormSpacing(section = (-1).dp) },
            { PayInFormSpacing(sectionTitle = (-1).dp) },
        ).forEach { build ->
            val failed =
                try {
                    build()
                    false
                } catch (expected: IllegalArgumentException) {
                    true
                }
            assertTrue("a negative gap was accepted", failed)
        }
    }

    @Test
    fun `a gap that is not a finite measurement is refused`() {
        // Dp.Unspecified is NaN, and NaN < 0.dp is false, so a check for negative alone passed it
        // here and failed later where a layout turns it into pixels.
        listOf(Dp.Unspecified, Dp.Infinity, Dp(Float.NEGATIVE_INFINITY), Dp(Float.NaN)).forEach { bad ->
            val failed =
                try {
                    PayInFormSpacing(content = bad)
                    false
                } catch (expected: IllegalArgumentException) {
                    true
                }
            assertTrue("$bad was accepted", failed)
        }
    }

    @Test
    fun `zero is a gap, and is allowed`() {
        assertEquals(0.dp, PayInFormSpacing(content = 0.dp).content)
    }
}
