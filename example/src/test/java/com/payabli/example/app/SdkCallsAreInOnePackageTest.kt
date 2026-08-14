package com.payabli.example.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every call into the Payabli SDK is in `sdk`, and nothing else names an SDK type.
 *
 * The point of this app is to be read, and the first question a reader has is which of it talks to the SDK.
 * A package answers that only while it stays true, and the `sdk` import that drifts into a screen is the one
 * nobody notices. So it is read out of the source rather than agreed.
 *
 * `src/main` only. A test doubles the SDK's types to build a fixture, which is the same work by a different
 * name.
 */
class SdkCallsAreInOnePackageTest {
    private val appSources: List<File> =
        File("src/main/java/com/payabli/example/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `nothing outside the sdk package imports the SDK`() {
        assertEquals("no source was read, so this proves nothing", true, appSources.size > 50)

        val offenders =
            appSources
                .filterNot { it.path.contains("/app/sdk/") }
                .filter { file -> file.readLines().any { it.startsWith("import com.payabli.sdk.") } }
                .map { it.path.substringAfter("/app/") }
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
                .filter { it.path.contains("/app/sdk/") }
                .count { file -> file.readLines().any { it.startsWith("import com.payabli.sdk.") } }

        assertEquals("sdk/ stopped calling the SDK", true, callers >= 5)
    }
}
