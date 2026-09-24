plugins {
    `java-platform`
    id("payabli.publish")
    id("payabli.quality")
}

// Dev module is :payabli-bom; it releases as the BOM artifact "sdk-android-bom".
extra["payabliArtifactId"] = "sdk-android-bom"

// Version BOM: publishes a POM-only artifact that pins a mutually-compatible set
// of Payabli SDK module versions (the Firebase BoM model). It manages versions
// only — depending on the BOM pulls no code.
// Coordinates are the published sdk-android-* family.
//
// A constraint advertises a coordinate, so a module named here is one that publishes.
dependencies {
    constraints {
        api("${project.group}:sdk-android-core:${project.version}")
        api("${project.group}:sdk-android-payin:${project.version}")
        api("${project.group}:sdk-android-taptopay:${project.version}")
        api("${project.group}:sdk-android-telemetry:${project.version}")
        api("${project.group}:sdk-android:${project.version}")
    }
}
