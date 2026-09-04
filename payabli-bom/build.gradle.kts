plugins {
    `java-platform`
    id("payabli.publish")
    id("payabli.quality")
}

// Dev module is :payabli-bom; it releases as the BOM artifact "sdk-android-bom".
extra["payabliArtifactId"] = "sdk-android-bom"

// Version BOM: publishes a POM-only artifact that pins a mutually-compatible set
// of Payabli SDK module versions (the Firebase BoM model). It manages versions
// only — depending on the BOM pulls no code. taptopay IS pinned here (safe: a BOM
// never forces resolution), even though the umbrella AAR deliberately omits it.
// Coordinates are the published sdk-android-* family.
// The group is read rather than written, so a change to `payabli.group` reaches these five without this
// file being touched. It was spelled out here once and went stale the first time that property moved.
dependencies {
    constraints {
        api("${project.group}:sdk-android-core:${project.version}")
        api("${project.group}:sdk-android-payin:${project.version}")
        api("${project.group}:sdk-android-taptopay:${project.version}")
        api("${project.group}:sdk-android-telemetry:${project.version}")
        api("${project.group}:sdk-android:${project.version}")
    }
}
