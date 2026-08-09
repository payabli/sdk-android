package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every field has a word for it, and no two fields share one.
 *
 * A field with no label crashes when it is drawn, and a field sharing another's label puts two boxes
 * on screen asking for the same thing. Neither is reachable from a unit test that renders nothing, so
 * this reads `strings.xml` and the mapping beside it.
 */
class PayInStringsTest {
    private val strings: String = File("src/main/res/values/strings.xml").readText()

    private val declaredNames: List<String> =
        Regex("""<string name="([^"]+)"""").findAll(strings).map { it.groupValues[1] }.toList()

    private val declaredPlurals: List<String> =
        Regex("""<plurals name="([^"]+)"""").findAll(strings).map { it.groupValues[1] }.toList()

    @Test
    fun `the resource file is where this test thinks it is`() {
        // Without this every assertion below passes on an empty file.
        assertTrue("strings.xml looks empty", declaredNames.size > 20)
        assertTrue("no plurals found", declaredPlurals.isNotEmpty())
    }

    @Test
    fun `every plural the code asks for is declared, and every declared one is read`() {
        val referenced =
            File("src/main/java/com/payabli/sdk/payin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    Regex("""R\.plurals\.([a-z_]+)""").findAll(file.readText()).map { it.groupValues[1] }
                }.toSet()

        assertEquals(
            "a composable names a plural that is not in strings.xml",
            emptyList<String>(),
            referenced.filterNot { it in declaredPlurals }.sorted(),
        )
        assertEquals(
            "a plural is declared and never read",
            emptyList<String>(),
            declaredPlurals.filterNot { it in referenced }.sorted(),
        )
    }

    @Test
    fun `every field has a label resource`() {
        val missing =
            PayInField.entries.filterNot { field ->
                "payabli_payin_field_${field.snakeCase()}" in declaredNames
            }

        assertEquals("a field with no label crashes when it is drawn", emptyList<PayInField>(), missing)
    }

    @Test
    fun `no two fields share a label`() {
        val labels =
            Regex("""<string name="payabli_payin_field_[^"]+">([^<]+)</string>""")
                .findAll(strings)
                .map { it.groupValues[1] }
                .toList()

        assertEquals("two boxes asking for the same thing", labels.size, labels.toSet().size)
    }

    @Test
    fun `every choice field has options declared for it`() {
        // A dropdown with nothing in it looks like a field that will not open.
        val choiceFields = PayInField.entries.filter { it.input == PayInFieldInput.Choice }
        assertTrue("no choice field to check", choiceFields.isNotEmpty())

        val sourced = File("src/main/java/com/payabli/sdk/payin/ui/PayInStrings.kt").readText()
        choiceFields.forEach { field ->
            assertTrue(
                "${field.name} has no options in PayInStrings.choices",
                sourced.contains("PayInField.${field.name} ->"),
            )
        }
    }

    @Test
    fun `every error case has a message`() {
        // A `when` over the sealed type is exhaustive at compile time, so a missing branch cannot
        // reach here. What it cannot check is that the branch names a resource that exists.
        val sourced = File("src/main/java/com/payabli/sdk/payin/ui/PayInStrings.kt").readText()
        val referenced =
            Regex("""R\.(?:string|plurals)\.(payabli_payin_error_[a-z_]+)""")
                .findAll(sourced)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue("no error strings referenced", referenced.isNotEmpty())
        assertEquals(
            "an error message names a resource that does not exist",
            emptyList<String>(),
            referenced.filterNot { it in declaredNames || it in declaredPlurals },
        )
    }

    @Test
    fun `every string the code asks for is declared`() {
        val sourced =
            File("src/main/java/com/payabli/sdk/payin/ui")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    Regex("""R\.string\.([a-z_]+)""").findAll(file.readText()).map { it.groupValues[1] }
                }.toSet()

        assertTrue("no resources referenced", sourced.isNotEmpty())
        assertEquals(
            "a composable names a string that is not in strings.xml",
            emptyList<String>(),
            sourced.filterNot { it in declaredNames }.sorted(),
        )
    }

    @Test
    fun `every declared string is used`() {
        // A string nobody reads is one a translator pays for and nobody sees.
        val sourced =
            File("src/main/java/com/payabli/sdk/payin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    Regex("""R\.string\.([a-z_]+)""").findAll(file.readText()).map { it.groupValues[1] }
                }.toSet()

        val fieldLabels = PayInField.entries.map { "payabli_payin_field_${it.snakeCase()}" }.toSet()
        assertEquals(
            "a string is declared and never read",
            emptyList<String>(),
            declaredNames.filterNot { it in sourced || it in fieldLabels }.sorted(),
        )
    }

    private fun PayInField.snakeCase(): String = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
}
