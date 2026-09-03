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
//
// **The instrumented half is off unless asked for, and that is not a default worth flipping.** AGP
// instruments the `debug` build type, and a library's `debug` variant is exactly what `:example` links, so
// leaving it on puts JaCoCo's classes into the sample's APK and through its dex merge. That merge ran out of
// heap in CI against the 2048m daemon `gradle.properties` sets: intermittently, twice green before it went
// red on a commit that touched only test sources. Only the job that reads the report needs the
// instrumentation, and that job builds no application module, so the cost belongs to it rather than to every
// build on every machine. `ci.yml`'s instrumented job passes the property.
//
// It is library-only for a second reason: the analysis skips `:example`, so instrumenting the sample would
// measure lines nothing reads.
val wantsInstrumentedCoverage: Boolean =
    providers.gradleProperty("payabli.instrumentedCoverage").orNull == "true"

plugins.withId("com.android.library") {
    if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
        extensions.configure<LibraryExtension> {
            buildTypes.named("debug") {
                enableUnitTestCoverage = true
            }
        }
    }
    if (wantsInstrumentedCoverage && layout.projectDirectory.dir("src/androidTest").asFile.isDirectory) {
        extensions.configure<LibraryExtension> {
            buildTypes.named("debug") {
                enableAndroidTestCoverage = true
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
