package com.payabli.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `payabli.api-surface` records the surface it is given, and no task lifecycle invokes its check. */
class ApiSurfaceConventionTest {
    @get:Rule
    val projectDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a declaration becoming public reaches the signature`() {
        writeProject()
        writeSource("Greeter.java", "class Greeter {\n    void greet() {}\n}\n")

        runner("metalavaGenerateSignature").build()
        val surfaceBefore = signature().readText()
        assertTrue(surfaceBefore, !surfaceBefore.contains("Greeter"))

        writeSource("Greeter.java", "public class Greeter {\n    public void greet() {}\n}\n")
        runner("metalavaGenerateSignature").build()
        val surfaceAfter = signature().readText()

        // The pair is the assertion: the recorded surface moved with the declaration, so a
        // generated file that matched the previous one would fail rather than pass.
        assertTrue(surfaceAfter, surfaceAfter.contains("public class Greeter"))
        assertFalse(surfaceAfter, surfaceAfter == surfaceBefore)
    }

    @Test
    fun `an annotated public declaration stays in the signature`() {
        writeProject()
        writeSource(
            "ForSiblingModules.java",
            "public @interface ForSiblingModules {\n}\n",
        )
        writeSource(
            "Annotated.java",
            "@ForSiblingModules\npublic class Annotated {\n}\n",
        )

        runner("metalavaGenerateSignature").build()

        // No annotation-based hiding is configured, so an annotated declaration is recorded like
        // any other. This is what keeps declarations annotated for sibling modules on the record.
        assertTrue(signature().readText().contains("public class Annotated"))
    }

    @Test
    fun `no task lifecycle invokes the signature check`() {
        writeProject()
        writeSource("Greeter.java", "class Greeter {\n    void greet() {}\n}\n")

        val result = runner("build", "--dry-run").build()

        assertFalse(result.output, result.output.contains("metalava"))
    }

    // java-library rather than an Android library, so the probe needs no Android SDK: the
    // convention plugin configures the same extension whichever module shape applies it. The
    // configuration cache is on, as the real build has it, so a plugin that cannot serialize
    // under it fails here rather than on every contributor's machine.
    private fun writeProject() {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "api-surface-probe"""")
        projectDir.newFile("gradle.properties").writeText("org.gradle.configuration-cache=true\n")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("payabli.api-surface")
            }

            repositories {
                google()
                mavenCentral()
            }
            """.trimIndent(),
        )
    }

    private fun writeSource(name: String, body: String) =
        projectDir.root.resolve("src/main/java").apply { mkdirs() }.resolve(name).writeText(body)

    private fun signature() = projectDir.root.resolve("api/release.txt")

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(*args)
}
