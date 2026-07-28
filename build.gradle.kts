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
        // Binary resources are not source. Without this the scanner tries to read
        // launcher icons as UTF-8 text and warns on every one.
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
    }
}

// Reports produced by ktlintCheck, lint and the unit-test coverage task.
subprojects {
    sonar {
        properties {
            val reports = layout.buildDirectory.dir("reports").get().asFile

            // Enumerated rather than globbed: this property takes a comma-separated list
            // of paths and does not expand wildcards. Listed per source set that exists,
            // so the scanner is never pointed at a report that cannot be produced.
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

            // Only set where the producing task exists, so the scanner is not pointed at
            // files that can never appear: the BOM has no Android block, and coverage is
            // enabled only for modules with tests (see payabli.quality).
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
