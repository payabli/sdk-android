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
    fun `nothing outside the sdk package names the SDK`() {
        assertEquals("no source was read, so this proves nothing", true, appSources.size > 50)

        val offenders =
            appSources
                .filterNot { it.inSdkPackage() }
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
                .filter { it.inSdkPackage() }
                .count { it.namesTheSdk() }

        assertEquals("sdk/ stopped calling the SDK", true, callers >= 5)
    }

    // An import is not the only way to reach a type: a fully qualified name needs none.
    private fun File.namesTheSdk(): Boolean = readText().contains("com.payabli.sdk.")

    // `File.path` carries the platform's separator, so a Windows checkout matches no forward slash.
    private fun File.inSdkPackage(): Boolean = invariantSeparatorsPath.contains("/app/sdk/")
}
