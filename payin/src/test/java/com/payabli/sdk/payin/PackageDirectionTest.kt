package com.payabli.sdk.payin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Packages depend inward: `form` reads `model` and `util`, and those two read no other package here. */
class PackageDirectionTest {
    private val root = File("src/main/java/com/payabli/sdk/payin")

    private fun importedPackages(pkg: String): Set<String> =
        File(root, pkg)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""^import com\.payabli\.sdk\.payin\.([a-z]+)\.""", RegexOption.MULTILINE)
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
            }.filter { it != pkg }
            .toSet()

    @Test
    fun `the sources are where this test thinks they are`() {
        // Without this every assertion below passes on an empty directory.
        assertTrue(File(root, "model").isDirectory && File(root, "form").isDirectory)
    }

    @Test
    fun `model and util import no other package`() {
        assertEquals(emptySet<String>(), importedPackages("model"))
        assertEquals(emptySet<String>(), importedPackages("util"))
    }

    @Test
    fun `form imports only model and util`() {
        assertTrue(importedPackages("form").all { it in setOf("model", "util") })
    }
}
