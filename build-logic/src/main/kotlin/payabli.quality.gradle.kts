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

// A module's consumer keep rules are an input to its unit tests.
//
// Two tests read `src/main/keepRules/rules.keep` from the source tree, because the rule is packaged into the
// AAR for an integrator's R8 rather than put on any classpath. Gradle cannot see that, so deleting a rule
// left the test task up to date and the suite green: the check that exists to catch a dropped keep rule was
// the one thing a dropped keep rule did not fail. Measured — it went red only under `--rerun-tasks`.
tasks.withType<Test>().configureEach {
    val rules = layout.projectDirectory.file("src/main/keepRules/rules.keep")
    if (rules.asFile.isFile) {
        inputs.file(rules).withPropertyName("consumerKeepRules").withPathSensitivity(PathSensitivity.RELATIVE)
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

// The same rule for application modules.
plugins.withId("com.android.application") {
    if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
        extensions.configure<ApplicationExtension> {
            buildTypes.named("debug") {
                enableUnitTestCoverage = true
            }
        }
    }
}
