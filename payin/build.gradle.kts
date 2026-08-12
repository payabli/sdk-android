plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // @Serializable needs this plugin per module; the runtime comes from :core. Absent, every serializer()
    // call reads as an unresolved reference.
    alias(libs.plugins.kotlin.serialization)
    id("payabli.publish")
    id("payabli.quality")
}

android {
    namespace = "com.payabli.sdk.payin"
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
    buildFeatures {
        compose = true
    }

    // Fixtures both test source sets need, as `:core` wires its loopback harness. An androidTest source set
    // cannot see `test` classes, and the retention questions are only answerable on a device, so the transport
    // doubles and the form fixtures are reachable from both. Compiled twice, once into each, so nothing here may
    // be JVM-only. `kotlin`, not `java`: AGP's built-in Kotlin support keeps its own source directories, and
    // adding to `java` alone leaves .kt files out of the Kotlin compilation.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }
}

// As :core does. Everything this module exists for is a surface an integrator calls, and Kotlin makes
// a declaration public when nothing says otherwise. This asks for the modifier to be written.
kotlin {
    explicitApi()
}

dependencies {
    // Capability modules depend on :core only, never on a sibling capability.
    api(project(":core"))

    // api: the public composables take a Modifier and carry @Composable in their signatures.
    // :payabli-android depends on this module, so the umbrella carries Compose.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)

    // This module's @Preview functions only.
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    // The clients are suspending, so their tests need a test dispatcher, as :core's and :taptopay's do.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // ComponentActivity, which the retention tests launch and recreate. ui-test-manifest declares it
    // in the test APK's manifest; this is what puts the class on the compile classpath.
    androidTestImplementation(libs.androidx.activity.compose)
    // The retention tests hold the flow in a ViewModel, which is how the KDoc tells a host to hold it.
    // Nothing in main needs it: the flow takes the host's scope, so no lifecycle type reaches the AAR.
    androidTestImplementation(libs.androidx.lifecycle.viewmodel)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose's idle waiting calls Espresso.onIdle, and the version ui-test-junit4 asks for throws on a
    // current device. The catalog entry says what breaks.
    androidTestImplementation(libs.androidx.espresso.core)
    // runTest, as the unit tests use it: the holder's submit suspends.
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
