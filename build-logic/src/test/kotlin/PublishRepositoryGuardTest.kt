package com.payabli.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `payabli.publish` publishes to a directory, and refuses a repository with any other scheme. */
class PublishRepositoryGuardTest {
    @get:Rule
    val projectDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the staging directory configures`() {
        writeProject(extra = "")

        val result = runner().build()

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `a remote publishing repository is refused, and the message says why`() {
        writeProject(
            extra = """
            publishing {
                repositories {
                    maven {
                        name = "Elsewhere"
                        url = uri("https://example.invalid/repo")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner().buildAndFail()

        // Any configuration failure satisfies buildAndFail, so the message is what identifies this one.
        assertTrue(result.output, result.output.contains("publishing repository 'Elsewhere' is https"))
        assertTrue(result.output, result.output.contains("only file is allowed"))
    }

    // No Android plugin, so no SDK is needed: the guard sits in the script body, where the publication
    // registrations sit behind pluginManager.withPlugin.
    private fun writeProject(extra: String) {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "guard-probe"""")
        projectDir.newFile("gradle.properties").writeText(
            """
            payabli.group=com.payabli
            payabli.version=0.0.0-probe
            """.trimIndent(),
        )
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                id("payabli.publish")
            }

            $extra
            """.trimIndent(),
        )
    }

    // `help` is enough: the guard runs in afterEvaluate, which every task invocation reaches.
    private fun runner(): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments("help")
}
