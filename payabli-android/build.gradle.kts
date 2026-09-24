plugins {
    alias(libs.plugins.android.library)
    id("payabli.publish")
    id("payabli.quality")
}

extra["payabliArtifactId"] = "sdk-android"

// Thin aggregate: no code of its own, it re-exports every capability module so a consumer can depend on
// one coordinate. :payin ships the Compose payment form, so this artifact carries the Compose runtime.
android {
    namespace = "com.payabli.sdk.bundle"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        // Card-present floor, inherited from :taptopay and required by the card reader.
        minSdk = 30

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
    api(project(":taptopay"))
    api(project(":telemetry"))
}
