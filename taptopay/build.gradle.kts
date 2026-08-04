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
        // target, so it is configured rather than hard-coded. Without it the tests that make a real
        // request are filtered out of the run, rather than skipped or failed.
        //
        // Filtered by `notClass` rather than by an annotation, and that is forced rather than chosen.
        // AGP applies `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=...` over whatever the
        // DSL set, measured both with and without the configuration cache, so any exclusion written on
        // that key disappears under the flag the nightly and the manual tier both pass. `notClass` is a
        // separate key that no documented command here uses. The cost is naming the class: a second
        // configuration-dependent test has to be added to this list.
        val cloudProjectNumber = providers.gradleProperty("payabli.cloudProjectNumber").orNull
        if (cloudProjectNumber != null) {
            testInstrumentationRunnerArguments["cloudProjectNumber"] = cloudProjectNumber
        } else {
            testInstrumentationRunnerArguments["notClass"] =
                "com.payabli.sdk.taptopay.attestation.platform.PlayIntegrityRealProjectTest"
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
