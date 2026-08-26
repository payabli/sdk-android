plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
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

        testApplicationId = "com.payabli.example.app"

        val excluded =
            buildList {
                val cloudProjectNumber = providers.gradleProperty("payabli.cloudProjectNumber").orNull
                if (cloudProjectNumber != null) {
                    testInstrumentationRunnerArguments["cloudProjectNumber"] = cloudProjectNumber
                } else {
                    add("com.payabli.sdk.taptopay.attestation.platform.PlayIntegrityRealProjectTest")
                }

                val entry = providers.gradleProperty("payabli.ttp.entry").orNull
                if (cloudProjectNumber != null && entry != null) {
                    testInstrumentationRunnerArguments["entry"] = entry

                    providers.gradleProperty("payabli.ttp.environment").orNull?.let {
                        testInstrumentationRunnerArguments["environment"] = it
                    }
                    providers.gradleProperty("payabli.ttp.tokenEndpoint").orNull?.let {
                        testInstrumentationRunnerArguments["tokenEndpoint"] = it
                    }
                } else {
                    // Both drive the real service under a real paypoint. The transaction pair needs an
                    // activated device to charge as, so it needs the attestation credentials too.
                    add("com.payabli.sdk.taptopay.enrollment.platform.DeviceActivationLiveTest")
                    add("com.payabli.sdk.taptopay.network.platform.TTPTransactionLiveTest")
                    add("com.payabli.sdk.taptopay.session.platform.TapToPaySessionLiveTest")
                }

                // Asserts an answer only a deployed service change produces, and this module ships ahead of
                // it. Excluded until a run says that change is live where it is pointed, so the tier stays
                // green on an environment that has not taken it. The test's own documentation says what to
                // check before passing this.
                if (providers.gradleProperty("payabli.ttp.entryPointRefusalDeployed").orNull != "true") {
                    add("com.payabli.sdk.taptopay.enrollment.platform.EntryPointRefusalLiveTest")
                }
            }
        if (excluded.isNotEmpty()) {
            testInstrumentationRunnerArguments["notClass"] = excluded.joinToString(",")
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
    implementation(libs.play.integrity)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(project(":testutils"))
    androidTestImplementation(project(":testutils"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
