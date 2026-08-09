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
 * It greps rather than renders because every CI job here runs without a device.
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
     * Named one by one, so widening the rule takes an edit here.
     */
    private val measurementExceptions =
        mapOf(
            "PayInIcons.kt" to "Material's icon grid is 24dp square, and the paths are drawn against it",
            "PayInSubmitButton.kt" to "the 48dp touch target is Android's guideline, and a spinner has no theme role",
            "ExpiryPickerDialog.kt" to
                "the list is bounded so a dialog of twenty-one years fits, and a row holds the 48dp target",
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
    fun `only one file reads MaterialTheme`() {
        // A second read is a value a caller's PayInFormStyle cannot reach, and it looks right on
        // screen because the theme it reads is usually the same one the style came from. The
        // colour rule below cannot see it: MaterialTheme.colorScheme.x is not a literal.
        val offenders =
            uiSources
                .filter { it.name != THEME_BOUNDARY }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> line.contains("MaterialTheme.") }
                        .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
                }

        assertEquals(
            "the theme is read in $THEME_BOUNDARY and turned into roles; everything else takes the style",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the file that reads MaterialTheme is still there`() {
        // Renaming it would leave the rule above passing over a module that reads the theme nowhere.
        assertTrue("$THEME_BOUNDARY is gone", uiSources.any { it.name == THEME_BOUNDARY })
    }

    @Test
    fun `the colour rule catches every way a colour can be written`() {
        // Each is a deliberate break. The rule read only Color(0x…) and the named constants, so the
        // two constructor forms below sat in a composable with it green.
        listOf(
            "Color(0xFF008BCE)",
            "Color(255, 0, 0)",
            "Color(red = 1f, green = 0f, blue = 0f)",
            "Color.Red",
            "background(Color.Magenta)",
        ).forEach { assertTrue("$it is not caught", COLOUR_LITERAL.containsMatchIn(it)) }
    }

    @Test
    fun `the colour rule leaves a theme role and a transparent alone`() {
        // A rule that flagged these would be turned off, and then it would catch nothing.
        listOf(
            "MaterialTheme.colorScheme.onSurface",
            "style.selectedContainer",
            "Color.Transparent",
        ).forEach { assertTrue("$it is flagged", !COLOUR_LITERAL.containsMatchIn(it)) }
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
        /** The one file that turns `MaterialTheme` into [com.payabli.sdk.payin.form.PayInThemeRoles]. */
        const val THEME_BOUNDARY = "PayabliPayInFormDefaults.kt"

        /**
         * Any `Color(...)` and any named `Color.Red`, but not `Color.Transparent`.
         *
         * `Color(0x…)` alone left `Color(255, 0, 0)` and `Color(red = 1f, …)` through, so a literal
         * could sit in a composable with this green.
         */
        val COLOUR_LITERAL =
            Regex("""Color\(|Color\.(Red|Green|Blue|Yellow|Cyan|Magenta|White|Black|Gray|LightGray|DarkGray)\b""")

        /** A number followed by a Compose unit, which is a measurement written into the code. */
        val MEASUREMENT = Regex("""\b\d+(\.\d+)?\.(dp|sp)\b""")
    }
}
