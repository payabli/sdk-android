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
// **taptopay is absent, and it has to travel with that module's publication.** It was pinned here
// while it published, on the grounds that a BOM never forces resolution, which is true and is not
// the whole of it: a constraint also advertises a coordinate. With the module withheld, an
// integrator who asks for sdk-android-taptopay and lets the BOM supply the version gets a version
// that resolves to nothing, where before they got the artifact. Restore this line in the same change
// that restores `id("payabli.publish")` in taptopay/build.gradle.kts.
dependencies {
    constraints {
        api("io.github.payabli:sdk-android-core:${project.version}")
        api("io.github.payabli:sdk-android-payin:${project.version}")
        api("io.github.payabli:sdk-android-telemetry:${project.version}")
        api("io.github.payabli:sdk-android:${project.version}")
    }
}
