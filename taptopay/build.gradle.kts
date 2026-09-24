// Card-present. Publishes as sdk-android-taptopay.
//
// Published to the QA channel only for now: `.github/scripts/publish_staging.py` refuses this coordinate
// on the release prefix, and lifting that is a deliberate edit there rather than something a release
// workflow can do by default.
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
                    add("com.payabli.sdk.taptopay.adapters.platform.FiservCardReaderLiveTest")
                    add("com.payabli.sdk.taptopay.adapters.platform.TapToPayChargeLiveTest")
                    add("com.payabli.sdk.taptopay.adapters.platform.FiservDiagnosticsLiveTest")
                    add("com.payabli.sdk.taptopay.adapters.platform.ChargeWithoutTapLiveTest")
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
    buildTypes {
        debug {
            // Off even when `payabli.instrumentedCoverage` asks for it, which the convention plugin honours
            // for every other library with an instrumented source set.
            //
            // Nothing runs this module's instrumented tier in CI, and nothing can until it has a job of its
            // own: building it resolves the card reader from a credentialed repository, so the job would hand
            // that credential to the third-party emulator action, which is the exposure the nightly's job split
            // exists to prevent. So a build that passes that property for :core and :payin gets no
            // instrumentation it could ever read here. Turn it back on with that job.
            enableAndroidTestCoverage = false
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
    // Strict, not the ordinary dependency this would otherwise be. Gradle settles a version conflict by
    // taking the highest, so an integrator whose graph reaches a newer reader would silently run a
    // configuration card-present was never certified against; a strict constraint fails that build. It
    // exists only in published module metadata, which is why it arrives with the publication. The mirror
    // decides which versions exist, never which one a graph picks.
    implementation(libs.fiserv.ttp) {
        version { strictly(libs.versions.fiservTtp.get()) }
    }
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
