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
    implementation("org.cyclonedx.bom:org.cyclonedx.bom.gradle.plugin:3.3.0")
}
