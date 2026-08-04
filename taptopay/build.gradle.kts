plugins {
    alias(libs.plugins.android.library)
    id("payabli.publish")
    id("payabli.quality")
}

android {
    namespace = "com.payabli.sdk.taptopay"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        // Card-present floor, required by the card reader dependency.
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Play Integrity project number. Not a secret, but environment-scoped and the shared quota
        // target, so it is configured rather than hard-coded. Absent, the tests needing it are filtered
        // out of the run; see RequiresCloudProject.
        val cloudProjectNumber = providers.gradleProperty("payabli.cloudProjectNumber").orNull
        if (cloudProjectNumber != null) {
            testInstrumentationRunnerArguments["cloudProjectNumber"] = cloudProjectNumber
        } else {
            testInstrumentationRunnerArguments["notAnnotation"] = "com.payabli.sdk.taptopay.RequiresCloudProject"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Capability modules depend on :core only, never on a sibling capability.
    api(project(":core"))
    implementation(libs.fiserv.ttp)
    // Attestation lives here rather than in :core because it is a card-present obligation: the platform
    // verdict gates arming the reader. Keeping it here is also what keeps the Play services artifacts off
    // the umbrella AAR, which omits this module, so a card-not-present integrator links none of them.
    //
    // implementation, not api: the attestation contract is ours and free of Play types, so nothing a host
    // app compiles against reaches these. They ride the runtime classpath only.
    implementation(libs.play.integrity)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    // testInstrumentationRunner names this class; without it the test APK dies with
    // ClassNotFoundException on AndroidJUnitRunner before any test runs. Same trap as :core.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
