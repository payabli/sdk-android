plugins {
    alias(libs.plugins.android.library)
    // The device endpoints' wire types are @Serializable. The runtime arrives on the compile classpath
    // through :core's api dependency, but the compiler plugin is per module and is what generates the
    // serializers, so without this line those types do not compile.
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

        // The test APK is self-instrumenting, so this is the package name the integrity verdict carries.
        // Attestation resolves the caller from that name and checks it against the paypoint's authorized
        // apps, so it must match the registered entry.
        //
        // It is also `:example`'s applicationId, and that collision is unavoidable: one identity is
        // registered, and whichever APK attests has to present it. The two cannot be installed at once —
        // the second install fails with INSTALL_FAILED_VERSION_DOWNGRADE, which names neither this setting
        // nor the sample app. Uninstall one before running the other.
        testApplicationId = "com.payabli.example.app"

        // Tests that reach a real service are filtered out when their configuration is absent. They are
        // filtered, not skipped: a standing skip looks the same as a regression that started skipping.
        //
        // Set `payabli.cloudProjectNumber` in ~/.gradle/gradle.properties. It is environment-scoped and
        // the shared Play Integrity quota target, so it is configured, not committed.
        //
        // Filtering uses `notClass`, which is forced. AGP applies a command-line
        // `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=...` over anything the DSL sets,
        // measured with and without the configuration cache, and the nightly and manual tier both pass
        // that flag. The cost: every configuration-dependent test is listed here **by name**, so renaming
        // or moving one silently re-enables it.
        //
        // One list, assigned once. A second assignment to `notClass` would drop the first.
        val excluded =
            buildList {
                val cloudProjectNumber = providers.gradleProperty("payabli.cloudProjectNumber").orNull
                if (cloudProjectNumber != null) {
                    testInstrumentationRunnerArguments["cloudProjectNumber"] = cloudProjectNumber
                } else {
                    add("com.payabli.sdk.taptopay.attestation.platform.PlayIntegrityRealProjectTest")
                }

                // The live tier also needs a paypoint. Bearers are fetched per call from the token server
                // on the development machine, reached over `adb reverse tcp:8787 tcp:8787`, so no token
                // value is ever passed on a command line or written to a properties file.
                val entry = providers.gradleProperty("payabli.ttp.entry").orNull
                if (cloudProjectNumber != null && entry != null) {
                    testInstrumentationRunnerArguments["entry"] = entry
                    // Both optional. The environment defaults to the deployment the test paypoints live
                    // on, and the endpoint to loopback.
                    providers.gradleProperty("payabli.ttp.environment").orNull?.let {
                        testInstrumentationRunnerArguments["environment"] = it
                    }
                    providers.gradleProperty("payabli.ttp.tokenEndpoint").orNull?.let {
                        testInstrumentationRunnerArguments["tokenEndpoint"] = it
                    }
                } else {
                    add("com.payabli.sdk.taptopay.enrollment.platform.DeviceActivationLiveTest")
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
    // Attestation lives here, not in :core: it is a card-present obligation, since the platform verdict
    // gates arming the reader. That also keeps the Play services artifacts off the umbrella AAR, which
    // omits this module, so a card-not-present integrator links none of them.
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
