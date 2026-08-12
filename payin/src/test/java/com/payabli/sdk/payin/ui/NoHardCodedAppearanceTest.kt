package com.payabli.sdk.payin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing in this module names a color or a measurement.
 *
 * The style resolution is tested separately and proves the resolver reads the host's theme. It cannot
 * prove that the composables use the resolved value, because a composable that ignored the style and
 * wrote `Color(0xFF008BCE)` would still pass it. This reads the source instead.
 *
 * It reads the source, because every CI job here runs without a device.
 */
class NoHardCodedAppearanceTest {
    /**
     * Everything that draws: the `ui` package, plus the composables that sit at the module root.
     *
     * Two rules rather than a file list. The public entry points live at the root, and naming one file here
     * would let the next one moved out of `ui` escape this check silently. The root is read shallowly on
     * purpose: `form` carries the style defaults, whose `dp` values are the thing being defaulted rather than
     * a literal smuggled into a layout.
     */
    private val uiSources: List<File> =
        (
            File("src/main/java/com/payabli/sdk/payin/ui").walkTopDown() +
                File("src/main/java/com/payabli/sdk/payin").listFiles().orEmpty().asSequence()
        ).filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * Files allowed to carry a color, each for a stated reason.
     *
     * One entry, and it names no color anything sees: an `ImageVector` path has to declare a fill,
     * and `Icon` paints its tint over it. Material's own icons are built the same way.
     *
     * The exception is a single line, so a color added anywhere else in that file is still caught.
     */
    private val colorExceptions =
        mapOf(
            "PayInIcons.kt" to "a vector path needs a fill, and Icon tints over it",
        )

    /** The only color a file in [colorExceptions] may carry. */
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
            "BRAND_MARK_WIDTH" to "the box a scheme's mark is letterboxed into, so the marks line up",
            "BRAND_MARK_HEIGHT" to "the same box",
        )

    @Test
    fun `the source directory is where this test thinks it is`() {
        // Without this the walk returns nothing and every assertion below passes on an empty list.
        assertTrue("no Kotlin found under payin/ui", uiSources.size >= 5)
    }

    @Test
    fun `the scan reaches the public entry point wherever it lives`() {
        // The form is the file that most needs these assertions, and the scan is two rules rather than a
        // path. Moved to the module root beside the other entry points it is still read; moved anywhere else
        // it is not, and every assertion below would keep passing without it.
        assertTrue(
            "PayabliPayInForm.kt is not in the scanned set",
            uiSources.any { it.name == "PayabliPayInForm.kt" },
        )
    }

    @Test
    fun `no composable names a color`() {
        val offenders =
            uiSources.flatMap { file ->
                val exempt = file.name in colorExceptions
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> COLOUR_LITERAL.containsMatchIn(line) }
                    .filterNot { (_, line) -> exempt && vectorFill.matches(line.trim()) }
                    .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
            }

        assertEquals(
            "a color written here does not follow the host's theme, whatever the resolver does",
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
        // color rule below cannot see it: MaterialTheme.colorScheme.x is not a literal.
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
    fun `the color rule catches every way a color can be written`() {
        // Each is a deliberate break. The rule read only Color(0x…) and the named constants, so the
        // two constructor forms below sat in a composable with it green.
        listOf(
            "Color(0xFF008BCE)",
            "Color(255, 0, 0)",
            "Color(red = 1f, green = 0f, blue = 0f)",
            "Color.Red",
            "background(Color.Magenta)",
            // Factory forms, which the constructor pattern does not match.
            "Color.hsl(210f, 0.8f, 0.5f)",
            "Color.hsv(210f, 0.8f, 0.5f)",
            "Color.parseColor(\"#FF0000\")",
        ).forEach { assertTrue("$it is not caught", COLOUR_LITERAL.containsMatchIn(it)) }
    }

    @Test
    fun `the color rule leaves a theme role and a transparent alone`() {
        // Theme roles and a transparent, which the rule has to leave alone to stay usable.
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
        val stale = (measurementExceptions.keys + colorExceptions.keys).filterNot { it in names }
        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `a vector goes through Icon, so the theme replaces its fill`() {
        // The color exception for PayInIcons holds while every one of them is tinted by Icon. Drawn with
        // Image, the declared black would show.
        uiSources.forEach { file ->
            assertTrue(
                "${file.name} draws a vector without Icon, so its declared fill would show",
                !file.readText().contains("Image(imageVector"),
            )
        }
    }

    @Test
    fun `only the brand marks are drawn untinted, and only from a drawable`() {
        // A scheme's mark carries its own colors and a tint would destroy it, so it is the one thing here
        // painted with Image. The colors live in the drawable XML, which is why the scan above stays whole:
        // no Kotlin file names them. Any other Image would be appearance this form decided for itself.
        val offenders =
            uiSources.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> line.contains("Image(") }
                    .filterNot { (_, _) -> file.name == BRAND_BADGE }
                    .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
            }

        assertEquals("a tint is the only way appearance reaches an icon here", emptyList<String>(), offenders)
    }

    @Test
    fun `the brand marks are the drawables the badge names, and each one exists`() {
        // A renamed drawable is a resource error at build time, and a mark left in the map for a scheme the
        // badge no longer draws is a file nothing renders.
        val badge = uiSources.single { it.name == BRAND_BADGE }.readText()
        val named =
            Regex("""R\.drawable\.(payabli_payin_brand_\w+)""")
                .findAll(badge)
                .map { it.groupValues[1] }
                .toSet()
        val onDisk =
            File("src/main/res/drawable")
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("payabli_payin_brand_") }
                .map { it.nameWithoutExtension }
                .toSet()

        assertEquals("the badge names six marks", 6, named.size)
        assertEquals(named, onDisk)
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

        /** The one file that draws a card scheme's own artwork. */
        const val BRAND_BADGE = "PayInFieldBox.kt"

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
