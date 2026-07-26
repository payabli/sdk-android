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
