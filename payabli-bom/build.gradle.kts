plugins {
    `java-platform`
    id("payabli.publish")
    id("payabli.quality")
}

extra["payabliArtifactId"] = "sdk-android-bom"

// A POM-only artifact pinning a mutually-compatible set of module versions: depending on it pulls no
// code. A constraint advertises a coordinate, so a module named here is one that publishes.
dependencies {
    constraints {
        api("${project.group}:sdk-android-core:${project.version}")
        api("${project.group}:sdk-android-payin:${project.version}")
        api("${project.group}:sdk-android-taptopay:${project.version}")
        api("${project.group}:sdk-android-telemetry:${project.version}")
        api("${project.group}:sdk-android:${project.version}")
    }
}
