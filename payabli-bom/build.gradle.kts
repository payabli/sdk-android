plugins {
    `java-platform`
    id("payabli.publish")
}

// Dev module is :payabli-bom; it releases as the BOM artifact "sdk-android-bom".
extra["payabliArtifactId"] = "sdk-android-bom"

// Version BOM: publishes a POM-only artifact that pins a mutually-compatible set
// of Payabli SDK module versions (the Firebase BoM model). It manages versions
// only — depending on the BOM pulls no code. taptopay IS pinned here (safe: a BOM
// never forces resolution), even though the umbrella AAR deliberately omits it.
// Coordinates are the published sdk-android-* family.
dependencies {
    constraints {
        api("io.github.payabli:sdk-android-core:${project.version}")
        api("io.github.payabli:sdk-android-payin:${project.version}")
        api("io.github.payabli:sdk-android-taptopay:${project.version}")
        api("io.github.payabli:sdk-android-telemetry:${project.version}")
        api("io.github.payabli:sdk-android:${project.version}")
    }
}
