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
     *
     * The exception is one line rather than the whole file, so a colour added anywhere else in that
     * file is still caught.
     */
    private val colourExceptions =
        mapOf(
            "PayInIcons.kt" to "a vector path needs a fill, and Icon tints over it",
        )

    /** The only colour a file in [colourExceptions] may carry. */
    private val vectorFill = Regex("""^fill = SolidColor\(Color\.Black\),$""")

    /**
     * Files allowed to carry a measurement outside a named constant, each for a stated reason.
     *
     * One file, and two lines inside it. `PayInSubmitButton.kt` and `ExpiryPickerDialog.kt` were
     * listed here and did not need to be: every measurement they carry is already a named constant,
     * which the rule allows anywhere, so the entries only widened it.
     */
    private val measurementExceptions =
        mapOf(
            "PayInIcons.kt" to "Material's icon grid is 24dp square, and the paths are drawn against it",
        )

    /** The only measurements a file in [measurementExceptions] may carry. */
    private val iconGrid = Regex("""^default(Width|Height) = 24\.dp,$""")

    /**
     * The named constants allowed to hold a measurement, each for a stated reason.
     *
     * A constant is not a reason on its own. Allowing any `private val x = 16.dp` let a size an
     * integrator cannot reach pass by being given a name, which is what `CLAUDE.md` calls a defect.
     * What is left is a platform guideline and a layout threshold, neither of which is a theme value.
     */
    private val measurementConstants =
        mapOf(
            "MINIMUM_TOUCH_TARGET" to "Android's minimum touch target, which is not the host's to set",
            "ROW_MIN_HEIGHT" to "the same target, for a row in the picker",
            "PAIRED_FIELD_MIN_WIDTH" to "where two fields stop fitting, which is a layout rule and not an appearance",
        )

    @Test
    fun `the source directory is where this test thinks it is`() {
        // Without this the walk returns nothing and every assertion below passes on an empty list.
        assertTrue("no Kotlin found under payin/ui", uiSources.size >= 5)
    }

    @Test
    fun `no composable names a colour`() {
        val offenders =
            uiSources.flatMap { file ->
                val exempt = file.name in colourExceptions
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> COLOUR_LITERAL.containsMatchIn(line) }
                    .filterNot { (_, line) -> exempt && vectorFill.matches(line.trim()) }
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
            uiSources.flatMap { file ->
                val exempt = file.name in measurementExceptions
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> MEASUREMENT.containsMatchIn(line) && !line.isApprovedConstant() }
                    .filterNot { (_, line) -> exempt && iconGrid.matches(line.trim()) }
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
            // Factories rather than constructors, which the constructor form does not cover.
            "Color.hsl(210f, 0.8f, 0.5f)",
            "Color.hsv(210f, 0.8f, 0.5f)",
            "Color.parseColor(\"#FF0000\")",
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
    fun `naming a constant is not on its own a reason to hold a measurement`() {
        // The rule that let SPINNER_SIZE = 16.dp through: it was private, it had a name, and an
        // integrator still could not change it.
        listOf(
            "private val SPINNER_SIZE = 16.dp",
            "private val CARD_HEIGHT = 220.dp",
            "private const val PADDING = 8.dp",
        ).forEach { assertTrue("$it is allowed", !it.isApprovedConstant()) }

        listOf(
            "private val MINIMUM_TOUCH_TARGET = 48.dp",
            "private val ROW_MIN_HEIGHT = 48.dp",
            "private val PAIRED_FIELD_MIN_WIDTH = 148.dp",
        ).forEach { assertTrue("$it is refused", it.isApprovedConstant()) }
    }

    @Test
    fun `every approved constant is one the module still declares`() {
        // A name left here after its constant goes silently widens the rule.
        val declared = uiSources.joinToString("\n") { it.readText() }
        measurementConstants.keys.forEach {
            assertTrue("$it is approved and no longer declared", declared.contains("val $it "))
        }
    }

    @Test
    fun `an excepted file may carry the icon grid and no other measurement`() {
        listOf("defaultWidth = 24.dp,", "defaultHeight = 24.dp,").forEach {
            assertTrue("the grid it exists for", iconGrid.matches(it))
        }
        listOf(
            "padding(37.dp)",
            "defaultWidth = 48.dp,",
            "modifier = Modifier.height(24.dp),",
            "viewportWidth = 24f,",
        ).forEach { assertTrue("$it would be let through", !iconGrid.matches(it)) }
    }

    @Test
    fun `an excepted file may carry the vector fill and nothing else`() {
        assertTrue("the fill it exists for", vectorFill.matches("fill = SolidColor(Color.Black),"))
        listOf(
            "background(Color.Red)",
            "tint = Color(0xFF008BCE)",
            "fill = SolidColor(Color.Red),",
            "val brand = Color(255, 0, 0)",
        ).forEach { assertTrue("$it would be let through", !vectorFill.matches(it)) }
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

    /** True for a declaration of one of the constants named in [measurementConstants]. */
    private fun String.isApprovedConstant(): Boolean =
        measurementConstants.keys.any { name ->
            trimStart().startsWith("private val $name ") || trimStart().startsWith("private const val $name ")
        }

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
            Regex(
                """Color\(""" +
                    """|Color\.(Red|Green|Blue|Yellow|Cyan|Magenta|White|Black|Gray|LightGray|DarkGray)\b""" +
                    """|Color\.(hsl|hsv|rgb|argb|valueOf|parseColor)\s*\(""",
            )

        /** A number followed by a Compose unit, which is a measurement written into the code. */
        val MEASUREMENT = Regex("""\b\d+(\.\d+)?\.(dp|sp)\b""")
    }
}
