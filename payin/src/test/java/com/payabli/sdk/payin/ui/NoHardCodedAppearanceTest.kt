package com.payabli.sdk.payin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing in this module names a colour or a measurement.
 *
 * The style resolution is tested separately and proves the resolver reads the host's theme. It cannot
 * prove that the composables use the resolved value, because a composable that ignored the style and
 * wrote `Color(0xFF008BCE)` would still pass it. This reads the source instead.
 *
 * Crude on purpose. A rule that greps beats one that needs a device, and every job here runs without
 * one.
 */
class NoHardCodedAppearanceTest {
    private val uiSources: List<File> =
        File("src/main/java/com/payabli/sdk/payin/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * Files allowed to carry a colour, each for a stated reason.
     *
     * One entry, and it names no colour anything sees: an `ImageVector` path has to declare a fill,
     * and `Icon` paints its tint over it. Material's own icons are built the same way.
     */
    private val colourExceptions =
        mapOf(
            "PayInIcons.kt" to "a vector path needs a fill, and Icon tints over it",
        )

    /**
     * Files allowed to carry a measurement, each for a stated reason.
     *
     * A list and not a pattern, so adding one is a decision somebody made on purpose.
     */
    private val measurementExceptions =
        mapOf(
            "PayInIcons.kt" to "Material's icon grid is 24dp square, and the paths are drawn against it",
            "PayInSubmitButton.kt" to "the 48dp touch target is Android's guideline, and a spinner has no theme role",
            "ExpiryPickerDialog.kt" to "the picker list is bounded so a dialog of twenty-one years still fits",
        )

    @Test
    fun `the source directory is where this test thinks it is`() {
        // Without this the walk returns nothing and every assertion below passes on an empty list.
        assertTrue("no Kotlin found under payin/ui", uiSources.size >= 5)
    }

    @Test
    fun `no composable names a colour`() {
        val offenders =
            uiSources.filter { it.name !in colourExceptions }.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> COLOUR_LITERAL.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
            }

        assertEquals(
            "a colour written here does not follow the host's theme, whatever the resolver does",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `a measurement appears only in a named constant, or in a file that says why`() {
        val offenders =
            uiSources
                .filter { it.name !in measurementExceptions }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> MEASUREMENT.containsMatchIn(line) && !line.isConstantDeclaration() }
                        .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
                }

        assertEquals(
            "spacing and sizes come from the style, so a measurement inline is one a caller cannot change",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every exception is a file that still exists`() {
        // An exception left behind after its file is renamed silently widens the rule.
        val names = uiSources.map { it.name }.toSet()
        val stale = (measurementExceptions.keys + colourExceptions.keys).filterNot { it in names }
        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `an icon is only ever drawn through Icon, which is what makes its fill safe`() {
        // The colour exception holds while every vector goes through Icon, which replaces the fill.
        // Painting one with Image would keep the black and stop following the theme.
        uiSources.forEach { file ->
            assertTrue(
                "${file.name} draws a vector without Icon, so its declared fill would show",
                !file.readText().contains("Image(imageVector"),
            )
        }
    }

    @Test
    fun `no composable reaches for a font family of its own`() {
        val offenders =
            uiSources.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> line.contains("FontFamily.") }
                    .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
            }

        assertEquals("type comes from the host's typography", emptyList<String>(), offenders)
    }

    private fun String.isConstantDeclaration(): Boolean =
        trimStart().startsWith("private val ") || trimStart().startsWith("private const ")

    private companion object {
        /** `Color(0xFF…)` and `Color.Red`, but not `Color.Transparent` or a role read from the theme. */
        val COLOUR_LITERAL =
            Regex("""Color\(0x|Color\.(Red|Green|Blue|Yellow|Cyan|Magenta|White|Black|Gray|LightGray|DarkGray)\b""")

        /** A number followed by a Compose unit, which is a measurement written into the code. */
        val MEASUREMENT = Regex("""\b\d+(\.\d+)?\.(dp|sp)\b""")
    }
}
