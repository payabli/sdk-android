package com.payabli.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `payabli.publish` publishes to a directory, and a task publishing anywhere else fails. */
class PublishRepositoryGuardTest {
    @get:Rule
    val projectDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the staging directory publishes`() {
        writeProject(extra = "")

        val result = runner("publishBomPublicationToStagingRepository").build()

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun `a remote repository is refused, and the message says why`() {
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

        val result = runner("publishBomPublicationToElsewhereRepository").buildAndFail()

        // On the message: this task would also fail by being unable to reach the host, and that failure
        // would satisfy buildAndFail while the guard was gone.
        assertTrue(result.output, result.output.contains("publishing repository 'Elsewhere' is https"))
        assertTrue(result.output, result.output.contains("only file is allowed"))
    }

    @Test
    fun `a repository turned remote after configuration is refused`() {
        writeProject(
            extra = """
            afterEvaluate {
                publishing.repositories.withType(MavenArtifactRepository::class.java).configureEach {
                    url = uri("https://example.invalid/repo")
                }
            }
            """.trimIndent(),
        )

        val result = runner("publishBomPublicationToStagingRepository").buildAndFail()

        assertTrue(result.output, result.output.contains("only file is allowed"))
    }

    // java-platform so the convention registers a publication, which is what gives the probe a publish
    // task to run. No Android plugin, so no SDK is needed.
    //
    // The configuration cache is on, as the real build has it. A probe writes its own gradle.properties
    // and inherits nothing, and the guard is shaped around what that cache will serialize, so without
    // this line the one failure mode the implementation exists to avoid cannot reach these tests.
    private fun writeProject(extra: String) {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "guard-probe"""")
        projectDir.newFile("gradle.properties").writeText(
            """
            payabli.group=com.payabli
            payabli.version=0.0.0-probe
            org.gradle.configuration-cache=true
            """.trimIndent(),
        )
        projectDir.newFile("build.gradle.kts").writeText(
            """
            import org.gradle.api.artifacts.repositories.MavenArtifactRepository

            plugins {
                `java-platform`
                id("payabli.publish")
            }

            $extra
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(*args)
}
