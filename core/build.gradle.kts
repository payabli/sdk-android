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
