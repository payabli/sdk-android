import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// Convention plugin: formatting and coverage gates. Style rules live in ../.editorconfig.
// Use ktlintCheck in CI; ktlintFormat may need --no-configuration-cache.

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    ignoreFailures.set(false)
    // CHECKSTYLE for analysis import, PLAIN for humans.
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

// Coverage, enabled only where tests exist: the task fails outright if nothing ran.
plugins.withId("com.android.library") {
    if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
        extensions.configure<LibraryExtension> {
            buildTypes.named("debug") {
                enableUnitTestCoverage = true
            }
        }
    }
}

// The same rule for application modules. The root build sets sonar.coverage.jacoco.xmlReportPaths on
// every subproject that has a src/test directory, without asking which Android plugin it applies, so
// without this branch :example's report path names a file nothing produces and every line in the
// sample app is measured as uncovered.
plugins.withId("com.android.application") {
    if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
        extensions.configure<ApplicationExtension> {
            buildTypes.named("debug") {
                enableUnitTestCoverage = true
            }
        }
    }
}
