plugins {
    `kotlin-dsl`
}

dependencies {
    // Android Gradle Plugin — needed so the convention plugin can configure the
    // com.android.library `LibraryExtension`. Keep the version in sync with
    // `agp` in ../gradle/libs.versions.toml.
    implementation("com.android.tools.build:gradle:9.2.1")
    // CycloneDX SBOM plugin, so the convention plugin can apply it to every
    // publishable module. Applied (not versioned) in payabli.publish.gradle.kts.
    // Keep in sync with `cyclonedx` in ../gradle/libs.versions.toml.
    implementation("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.3.0")
    // ktlint, applied by payabli.quality.gradle.kts. 14.1.0 is the floor: earlier
    // versions report no violations at all on Gradle 9 with AGP's built-in Kotlin.
    // Keep in sync with `ktlintGradle` in ../gradle/libs.versions.toml.
    implementation("org.jlleitschuh.gradle.ktlint:org.jlleitschuh.gradle.ktlint.gradle.plugin:14.2.0")
    // TestKit runs a real build against a synthetic project, which is the only way to reach a
    // convention plugin: this is an included build, so no task in the main build runs these tests
    // and ci.yml invokes them directly.
    testImplementation(gradleTestKit())
    // Keep in sync with `junit` in ../gradle/libs.versions.toml.
    testImplementation("junit:junit:4.13.2")
}
