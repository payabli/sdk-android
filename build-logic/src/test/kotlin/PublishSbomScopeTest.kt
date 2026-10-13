package com.payabli.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `payabli.publish` scopes a published module's bill of materials to its runtime graph. */
class PublishSbomScopeTest {
    @get:Rule
    val projectDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `a library's bill of materials lists its runtime graph and no build tooling`() {
        writeAndroidProject()

        val result = runner("cyclonedxDirectBom").build()

        // The probe applies the formatting convention, so a scan that reached build tooling would
        // resolve its engine and list it as a component of the published graph. Every component
        // entry carries a bom-ref, so the two together say the graph is populated and scoped.
        val sbom = projectDir.root.resolve("build/reports/cyclonedx-direct/bom.json").readText()
        assertTrue(result.output, sbom.contains("\"name\" : \"junit\""))
        assertTrue(result.output, sbom.contains("\"version\" : \"4.13.2\""))
        assertFalse(result.output, sbom.contains("ktlint"))
    }

    @Test
    fun `the version BOM's bill of materials is empty`() {
        writeBomProject()

        runner("cyclonedxBom", "cyclonedxDirectBom").build()

        // A platform publishes constraints and no code, so an empty graph is the record. The
        // document's own entry carries the only bom-ref, so a second one is a component leaking in.
        val sbom = projectDir.root.resolve("build/reports/cyclonedx-direct/bom.json").readText()
        assertEquals(1, Regex("bom-ref").findAll(sbom).count())
    }

    // An Android library, because the scoping names the Android variant's runtime classpath: a
    // project without that configuration would read as empty either way, which is the silent
    // failure this test exists to catch.
    private fun writeAndroidProject() {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "sbom-scope-probe"""")
        projectDir.newFile("gradle.properties").writeText(
            """
            payabli.group=com.payabli
            payabli.version=0.0.0-probe
            org.gradle.configuration-cache=true
            """.trimIndent(),
        )
        projectDir.newFile("local.properties")
            .writeText("sdk.dir=${System.getenv("ANDROID_HOME") ?: androidHomeFromLocalProperties()}\n")
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.library")
                id("payabli.publish")
                id("payabli.quality")
            }

            repositories {
                google()
                mavenCentral()
            }

            android {
                namespace = "com.payabli.probe"
                compileSdk {
                    version =
                        release(36) {
                            minorApiLevel = 1
                        }
                }
                defaultConfig {
                    minSdk = 23
                }
            }

            dependencies {
                implementation("junit:junit:4.13.2")
            }
            """.trimIndent(),
        )
    }

    private fun writeBomProject() {
        projectDir.newFile("settings.gradle.kts").writeText("""rootProject.name = "bom-scope-probe"""")
        projectDir.newFile("gradle.properties").writeText(
            """
            payabli.group=com.payabli
            payabli.version=0.0.0-probe
            org.gradle.configuration-cache=true
            """.trimIndent(),
        )
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                `java-platform`
                id("payabli.publish")
            }

            repositories {
                mavenCentral()
            }
            """.trimIndent(),
        )
    }

    private fun androidHomeFromLocalProperties(): String {
        val line = java.io.File("../local.properties").useLines { lines ->
            lines.firstOrNull { it.startsWith("sdk.dir=") }
        }
        return line?.removePrefix("sdk.dir=") ?: ""
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(*args)
}
