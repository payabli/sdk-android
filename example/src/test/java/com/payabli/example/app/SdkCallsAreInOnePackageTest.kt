package com.payabli.example.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every call into the Payabli SDK is in `sdk` or `demo/simple`, so a reader finds them by package. Reads every
 * variant's app sources and no test source, since a test doubles the SDK's types to build a fixture.
 */
class SdkCallsAreInOnePackageTest {
    private val appSources: List<File> =
        // Source-set roots, not `java`: AGP compiles `src/<set>/kotlin` too.
        listOf("src/main", "src/cardPresent", "src/cardNotPresent").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    @Test
    fun `nothing outside the sdk package names the SDK`() {
        assertEquals("no source was read, so this proves nothing", true, appSources.size > 50)

        val offenders =
            appSources
                .filterNot { it.inACallingPackage() }
                .filter { it.namesTheSdk() }
                .map { it.invariantSeparatorsPath.substringAfter("/app/") }
                .sorted()

        assertEquals(
            "these reach the SDK from outside sdk/: move the call, or hand back a type this app owns",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the sdk package is where the calls actually are`() {
        // The rule above is satisfied by an sdk package that does nothing. This one fails if the calls have
        // drained out of it.
        val callers =
            appSources
                .filter { it.invariantSeparatorsPath.contains("/app/sdk/") }
                .count { it.namesTheSdk() }

        assertEquals("sdk/ stopped calling the SDK", true, callers >= 5)
    }

    @Test
    fun `the simple screen is one file and it calls the SDK`() {
        // The second package exists for one screen. A reader sent there has to find the calls, and more than
        // one file there means it has grown the layer it was written to avoid.
        val simple = appSources.filter { it.invariantSeparatorsPath.contains("/app/demo/simple/") }

        assertEquals(simple.map { it.name }.toString(), 1, simple.size)
        assertEquals("the simple screen stopped calling the SDK", true, simple.all { it.namesTheSdk() })
    }

    // An import is not the only way to reach a type: a fully qualified name needs none.
    private fun File.namesTheSdk(): Boolean = readText().contains("com.payabli.sdk.")

    // `File.path` carries the platform's separator, so a Windows checkout matches no forward slash.
    private fun File.inACallingPackage(): Boolean =
        invariantSeparatorsPath.contains("/app/sdk/") || invariantSeparatorsPath.contains("/app/demo/simple/")
}
