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
    }
}

// Reports produced by ktlintCheck, lint and the unit-test coverage task.
subprojects {
    sonar {
        properties {
            val reports = layout.buildDirectory.dir("reports").get().asFile
            property("sonar.kotlin.ktlint.reportPaths", "$reports/ktlint/**/*.xml")
            property("sonar.androidLint.reportPaths", "$reports/lint-results-debug.xml")
            property("sonar.coverage.jacoco.xmlReportPaths", "$reports/coverage/test/debug/report.xml")
        }
    }
}
