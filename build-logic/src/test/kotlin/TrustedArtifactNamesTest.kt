package com.payabli.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `verifyTrustedArtifactNames` fails on a resolved sources or javadoc jar, and passes without one. */
class TrustedArtifactNamesTest {
    @get:Rule
    val projectDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a project resolving only plain jars passes`() {
        writeProject(dependency = "probe:lib:1.0")

        val result = runner("verifyTrustedArtifactNames").build()

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `a resolved sources jar fails, and the message names it`() {
        writeProject(dependency = "probe:lib:1.0:sources")

        val result = runner("verifyTrustedArtifactNames").buildAndFail()

        assertTrue(result.output, result.output.contains("lib-1.0-sources.jar"))
        assertTrue(result.output, result.output.contains("never checked them"))
    }

    @Test
    fun `a resolved javadoc jar fails`() {
        writeProject(dependency = "probe:lib:1.0:javadoc")

        val result = runner("verifyTrustedArtifactNames").buildAndFail()

        assertTrue(result.output, result.output.contains("lib-1.0-javadoc.jar"))
    }

    @Test
    fun `check runs the guard`() {
        writeProject(dependency = "probe:lib:1.0:sources")

        val result = runner("check").buildAndFail()

        assertTrue(result.output, result.output.contains("lib-1.0-sources.jar"))
    }

    @Test
    fun `a build script classpath holding a sources jar fails before any task runs`() {
        writeBuildScriptProject(dependency = "probe:lib:1.0:sources")

        val result = runner("help").buildAndFail()

        assertTrue(result.output, result.output.contains("lib-1.0-sources.jar"))
        assertTrue(result.output, result.output.contains("trusts without checking"))
        assertTrue(result.output, !result.output.contains("Task :help"))
    }

    @Test
    fun `a build script classpath of plain jars configures`() {
        writeBuildScriptProject(dependency = "probe:lib:1.0")

        val result = runner("help").build()

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
    }

    // The settings script the real build applies, copied in from this repository.
    private fun writeBuildScriptProject(dependency: String) {
        writeRepository()
        val hook = java.io.File("../gradle/trusted-artifact-names.settings.gradle.kts")
        projectDir.newFolder("gradle")
        hook.copyTo(projectDir.root.resolve("gradle/trusted-artifact-names.settings.gradle.kts"))
        projectDir.newFile("settings.gradle.kts").writeText(
            """
            rootProject.name = "trust-probe"
            apply(from = "gradle/trusted-artifact-names.settings.gradle.kts")
            """.trimIndent(),
        )
        projectDir.newFile("build.gradle.kts").writeText(
            """
            buildscript {
                repositories {
                    maven { url = uri("repo") }
                }
                dependencies {
                    classpath("$dependency")
                }
            }
            """.trimIndent(),
        )
    }

    // The configuration cache is on, as the real build has it.
    private fun writeProject(dependency: String) {
        writeRepository()
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "trust-probe"""")
        projectDir.newFile("gradle.properties").writeText("org.gradle.configuration-cache=true")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                id("payabli.trusted-artifacts")
            }

            repositories {
                maven { url = uri("repo") }
            }

            dependencies {
                implementation("$dependency")
            }
            """.trimIndent(),
        )
    }

    // One module with a plain, a sources and a javadoc jar, so no network is needed.
    private fun writeRepository() {
        val module = projectDir.newFolder("repo", "probe", "lib", "1.0")
        module.resolve("lib-1.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>probe</groupId>
              <artifactId>lib</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent(),
        )
        listOf("lib-1.0.jar", "lib-1.0-sources.jar", "lib-1.0-javadoc.jar").forEach {
            module.resolve(it).writeBytes(EMPTY_ZIP)
        }
    }

    private companion object {
        // A valid zip with no entries, so a jar failing to open cannot stand in for the guard failing.
        val EMPTY_ZIP = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18)
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(*args)
}
