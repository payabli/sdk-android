package com.payabli.sdk.payin.form

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The component takes its appearance from the host's theme and names none of its own.
 *
 * That claim is the reason this module resolves style through a plain function instead of building it
 * inside a composable: a form that hard-coded a colour would render correctly under the theme it was
 * written against and pass every screenshot taken there. Here it fails, because resolving under two
 * different themes has to produce two different styles.
 */
class PayInFormStyleTest {
    private val light =
        PayInThemeRoles(
            onSurface = Color(0xFF1A1C1E),
            onSurfaceVariant = Color(0xFF43474E),
            error = Color(0xFFBA1A1A),
            titleType = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
            subtitleType = TextStyle(fontSize = 14.sp),
            sectionTitleType = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            labelType = TextStyle(fontSize = 12.sp),
            supportingType = TextStyle(fontSize = 12.sp),
            fieldShape = RoundedCornerShape(4.dp),
        )

    /** Deliberately unlike [light] in every role, so nothing can pass by coincidence. */
    private val other =
        PayInThemeRoles(
            onSurface = Color(0xFFE3E2E6),
            onSurfaceVariant = Color(0xFFC3C7CF),
            error = Color(0xFFFFB4AB),
            titleType = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Black),
            subtitleType = TextStyle(fontSize = 18.sp),
            sectionTitleType = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            labelType = TextStyle(fontSize = 9.sp),
            supportingType = TextStyle(fontSize = 10.sp),
            fieldShape = CutCornerShape(12.dp),
        )

    @Test
    fun `every colour in the style follows the theme it was resolved under`() {
        // Colour by colour, and not TextStyle by TextStyle. Comparing whole styles passes as soon as
        // any part of one differs, so a hard-coded colour survives it on the strength of the font
        // size next to it. Measured: pinning the section title to a brand blue left this green.
        val a = resolvePayInFormStyle(light)
        val b = resolvePayInFormStyle(other)

        assertNotEquals("title", a.title.color, b.title.color)
        assertNotEquals("subtitle", a.subtitle.color, b.subtitle.color)
        assertNotEquals("sectionTitle", a.sectionTitle.color, b.sectionTitle.color)
        assertNotEquals("label", a.label.color, b.label.color)
        assertNotEquals("supporting", a.supporting.color, b.supporting.color)
        assertNotEquals("error", a.error.color, b.error.color)
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
    fun `text colours come from the role each one belongs to`() {
        val style = resolvePayInFormStyle(light)

        assertEquals(light.onSurface, style.title.color)
        assertEquals(light.onSurface, style.sectionTitle.color)
        assertEquals(light.onSurfaceVariant, style.subtitle.color)
        assertEquals(light.onSurfaceVariant, style.label.color)
        assertEquals(light.onSurfaceVariant, style.supporting.color)
        assertEquals(light.error, style.error.color)
    }

    @Test
    fun `type comes from the role, and the colour does not replace the rest of it`() {
        // copy(color = ...) and not a fresh TextStyle: the host's size, weight and family survive.
        val style = resolvePayInFormStyle(light)
        assertEquals(light.titleType.fontSize, style.title.fontSize)
        assertEquals(light.titleType.fontWeight, style.title.fontWeight)
        assertEquals(light.sectionTitleType.fontWeight, style.sectionTitle.fontWeight)
    }

    @Test
    fun `the error style is the supporting type in the error colour`() {
        // One role decides the size of both, so a host that enlarges its supporting text enlarges the
        // message under a field with it.
        val style = resolvePayInFormStyle(light)
        assertEquals(light.supportingType.fontSize, style.error.fontSize)
        assertEquals(light.error, style.error.color)
    }

    // --- overrides ---

    @Test
    fun `an override replaces one value and leaves the rest following the theme`() {
        // The whole reason overrides are per property. Changing one colour must not cost the caller
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
                ),
            )

        listOf(style.title, style.subtitle, style.sectionTitle, style.label, style.supporting, style.error)
            .forEach { assertEquals(text, it) }
        assertEquals(shape, style.fieldShape)
        assertEquals(spacing, style.spacing)
    }

    @Test
    fun `field colours are the host's Material defaults until a caller supplies their own`() {
        // null and not a set this module invents: Material's own already follow the host theme, and
        // reimplementing them here is how a component stops matching the app around it.
        assertNull(resolvePayInFormStyle(light).fieldColors)
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
    fun `zero is a gap, and is allowed`() {
        assertEquals(0.dp, PayInFormSpacing(content = 0.dp).content)
    }
}
