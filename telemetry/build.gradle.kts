plugins {
    alias(libs.plugins.android.library)
    // @Serializable needs this plugin per module; the runtime comes from :core. Absent, every serializer()
    // call reads as an unresolved reference.
    alias(libs.plugins.kotlin.serialization)
    id("payabli.publish")
    id("payabli.quality")
    id("payabli.api-surface")
}

android {
    namespace = "com.payabli.sdk.telemetry"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        // Card-not-present floor; see :core.
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // Depends on :core only. Every payment module depends on this one.
    api(project(":core"))

    // The process lifecycle, for the flush as the app goes away.
    implementation(libs.androidx.lifecycle.process)
    testImplementation(project(":testutils"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
}
