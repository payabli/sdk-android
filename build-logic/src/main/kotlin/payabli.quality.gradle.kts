import com.android.build.api.dsl.LibraryExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Convention plugin: formatting and static-analysis gates shared by every module.
// Applied via `id("payabli.quality")`. Style rules themselves live in ../.editorconfig.
//
// Use `ktlintCheck` in CI. `ktlintFormat` is not configuration-cache safe in the
// current plugin release; run it with --no-configuration-cache.

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    ignoreFailures.set(false)
    // CHECKSTYLE is the machine-readable form consumed by static-analysis reporting;
    // PLAIN is what a developer reads in the console.
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

// Coverage for library modules, so analysis reports on measured code rather than nothing.
// Guarded twice: this plugin also applies to the BOM, which has no Android block, and to the
// aggregate module, which has no tests of its own. Requesting a coverage report where nothing
// ran fails the task outright rather than reporting zero, so it is enabled only where tests
// exist. A module gains coverage automatically as soon as it gains a test source set.
plugins.withId("com.android.library") {
    if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
        extensions.configure<LibraryExtension> {
            buildTypes.named("debug") {
                enableUnitTestCoverage = true
            }
        }
    }
}
