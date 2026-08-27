plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    id("payabli.publish")
    id("payabli.quality")
}

android {
    namespace = "com.payabli.sdk.core"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        // Card-not-present floor. Card-present carries a higher floor of its own; see :taptopay.
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The version every module reports, taken from the same property the publish convention reads, so a
        // release cannot ship an artifact whose reported version is a stale literal. Read here rather than in
        // each module: `PayabliSdkVersion` is what the rest of the SDK names.
        buildConfigField(
            "String",
            "SDK_VERSION",
            "\"${providers.gradleProperty("payabli.version").get()}\"",
        )

        // Read by DeviceIdentifierFactory, as one of the three digest inputs. A fixed literal, so no
        // escaping applies, and deliberately not this module's namespace: the value identifies the SDK as a
        // whole rather than the module that happens to hold the code.
        //
        // **Changing this changes the identifier every device reports**, so every registered device
        // registers again as a stranger and owes a fresh activation code. It moved once, on 2026-08-25, when
        // the derivation was lifted out of the card-present module; card-present had not shipped, so the cost
        // was qa and sandbox re-registering. It should not move again.
        buildConfigField("String", "SDK_IDENTIFIER", "\"com.payabli.sdk\"")
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Fixtures both test source sets need. An androidTest source set cannot see `test` classes, and the
    // Android-only transport behaviours can only be shown on a device, so the loopback harness has to be
    // reachable from both. Compiled twice, once into each, which is why nothing here may be JVM-only.
    // `kotlin`, not `java`: AGP's built-in Kotlin support keeps its own source directories, and adding to
    // `java` alone leaves .kt files out of the Kotlin compilation. The default `src/<set>/java` dir is on
    // both lists, which is why the existing tests compile from a directory named `java`.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }
}

kotlin {
    explicitApi()
}

dependencies {
    // api, not implementation: @RestrictTo annotations sit on :core's cross-artifact surface, so
    // sibling modules need them on their compile classpath for Lint to read.
    api(libs.androidx.annotation)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // The shared fixtures. It depends on :core, and this direction is test-only, so there is no cycle:
    // :testutils compiles against :core's main, and only :core's test compilations see :testutils.
    testImplementation(project(":testutils"))
    // Same direction and the same reason. `:core` starts reporting by looking the telemetry module up by
    // name, and the only way to test that it finds one is for one to be on the classpath. Test-only: nothing
    // in `:core`'s main source set may name this module.
    testImplementation(project(":telemetry"))
    androidTestImplementation(project(":testutils"))
    // The same direction and reason as the line above, on the device side: the module has to be present for
    // an instrumented test to show that the session finds it and starts reporting on real Android.
    androidTestImplementation(project(":telemetry"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    // testInstrumentationRunner names this class, and nothing else on the androidTest classpath provides
    // it. Absent, the test APK installs and then dies with ClassNotFoundException before any test runs.
    androidTestImplementation(libs.androidx.test.runner)
    // The shared harness and the transport tests it serves are the same code in both source sets, so
    // androidTest needs the same two: JUnit4 assertions and runTest.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/** The variable the live telemetry check reads its record path from. Named here and in `TelemetryLiveTest`. */
val telemetryLiveRecord = "PAYABLI_TELEMETRY_LIVE_RECORD"

// That check sends a real batch and reads a record file the endpoint writes, so it is excluded by name unless
// the path is configured. An `Assume` reported a standing skip on every ordinary run, and a permanent skip
// cannot be told apart from a regression that started skipping. Same discipline as `:payin`'s live tier,
// which excludes by name when its properties are absent.
tasks.withType<Test>().configureEach {
    if (providers.environmentVariable(telemetryLiveRecord).orNull.isNullOrBlank()) {
        filter.excludeTestsMatching("com.payabli.sdk.core.telemetry.TelemetryLiveTest")
    }
}
