plugins {
    alias(libs.plugins.android.library)
    id("payabli.publish")
}

// Dev module is :payabli-android; it releases as the umbrella artifact "sdk-android".
extra["payabliArtifactId"] = "sdk-android"

// Thin aggregate ("umbrella") artifact: no code of its own, it just re-exports the
// commonly-integrated modules so consumers can depend on a single coordinate,
// io.github.payabli:payabli-android (the Stripe-style <brand>-<platform> umbrella).
// Card-present (payabli-taptopay) is intentionally EXCLUDED so the Fiserv-bearing
// dependency stays opt-in; consumers who need Tap to Pay add payabli-taptopay
// explicitly (its version is pinned by the BOM).
android {
    namespace = "com.payabli.sdk.bundle"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 19

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core"))
    api(project(":payin"))
    api(project(":telemetry"))

}
