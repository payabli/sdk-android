package com.payabli.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `payabli.publish` publishes to a directory, and a remote repository is refused.
 *
 * Nothing else can fail when that stops being true: a remote repository added back configures and
 * publishes green, and the artifacts go somewhere no consumer reads.
 */
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

        // On the message and not only on the failure: a configuration error for any other reason
        // fails too, and would pass this test while the guard was gone.
        assertTrue(result.output, result.output.contains("publishing repository 'Elsewhere' is https"))
        assertTrue(result.output, result.output.contains("only file is allowed"))
    }

    /**
     * A project applying `payabli.publish` and nothing else.
     *
     * No Android plugin, so the guard is reached without an SDK installed: it sits in the script body
     * where the publication registrations sit behind `pluginManager.withPlugin`.
     */
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
