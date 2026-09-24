plugins {
    alias(libs.plugins.android.library)
    id("payabli.quality")
}

// Test fixtures, not published. The group is still set, because Lint enforces @RestrictTo(LIBRARY_GROUP)
// by comparing Maven group ids and a module with none is outside every group.
//
// A dependency on :taptopay would pull the card reader into every consumer, and the main CI job holds no
// credential for it, so card-present fixtures stay there.
group = providers.gradleProperty("payabli.group").get()

android {
    namespace = "com.payabli.sdk.testutils"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        // Card-not-present floor; see :core.
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // api, not implementation: a consumer's test holds these types directly, and the @RestrictTo
    // annotations have to stay on its compile classpath for Lint to read.
    api(project(":core"))
    // The loopback harness throws AssertionError from close() and its callers assert with JUnit, so this
    // is part of the surface rather than an implementation detail.
    api(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
