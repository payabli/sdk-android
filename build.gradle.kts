// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.sonarqube)
}

sonar {
    properties {
        property("sonar.projectKey", "payabli_sdk-android")
        property("sonar.organization", "payabli")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sourceEncoding", "UTF-8")
        // Binary resources are not source.
        property(
            "sonar.exclusions",
            listOf(
                "**/build/**",
                "**/*.webp",
                "**/*.png",
                "**/*.jpg",
                "**/*.jpeg",
                "**/*.gif",
                "**/*.ttf",
                "**/*.otf",
            ).joinToString(","),
        )
        // Coverage only, so these files still get issue detection. A `platform` package binds directly to an
        // Android API with no JVM implementation, Keystore and `android.util.*`, so no unit test can reach a
        // line of it and the instrumented tier is what covers it. Kept as a package rule rather than a list
        // of files, so the boundary is something the code states rather than something this file remembers.
        // See CLAUDE.md "Testing" for what belongs there.
        property("sonar.coverage.exclusions", "**/platform/**")
    }
}

// Report paths for analysis. See mobile-sdk brain, reference/tooling.md.
subprojects {
    sonar {
        properties {
            val reports = layout.buildDirectory.dir("reports").get().asFile

            // Enumerated: this property is a comma-separated list, not a glob.
            val ktlintTasks = buildList {
                add("ktlintKotlinScriptCheck")
                if (layout.projectDirectory.dir("src/main").asFile.isDirectory) {
                    add("ktlintMainSourceSetCheck")
                }
                if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
                    add("ktlintTestSourceSetCheck")
                }
                if (layout.projectDirectory.dir("src/androidTest").asFile.isDirectory) {
                    add("ktlintAndroidTestSourceSetCheck")
                }
            }
            property(
                "sonar.kotlin.ktlint.reportPaths",
                ktlintTasks.joinToString(",") { "$reports/ktlint/$it/$it.xml" },
            )

            // Set only where the producing task exists.
            plugins.withId("com.android.library") {
                property("sonar.androidLint.reportPaths", "$reports/lint-results-debug.xml")
            }
            plugins.withId("com.android.application") {
                property("sonar.androidLint.reportPaths", "$reports/lint-results-debug.xml")
            }
            if (layout.projectDirectory.dir("src/test").asFile.isDirectory) {
                property(
                    "sonar.coverage.jacoco.xmlReportPaths",
                    "$reports/coverage/test/debug/report.xml",
                )
            }
        }
    }
}
