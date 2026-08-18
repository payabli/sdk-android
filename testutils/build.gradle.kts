plugins {
    alias(libs.plugins.android.library)
    id("payabli.quality")
}

// Fixtures the SDK's own modules test against. Not published, and it could not usefully be: every type
// these doubles stand in for is @RestrictTo(LIBRARY_GROUP), so nothing here is implementable outside this
// Maven group. `payabli.publish` is deliberately absent and there is no BOM constraint.
//
// It depends on :core and on nothing else. A dependency on :taptopay would pull the card reader artifact
// from its credentialed registry into every consumer, and the main CI job builds without that credential
// so fork pull requests can run at all. Card-present fixtures therefore stay in :taptopay.
// The group, without the publishing that normally carries it. @RestrictTo(LIBRARY_GROUP) is enforced by
// comparing Maven group ids, so a module that does not set one is outside every group and Lint refuses the
// interfaces these fixtures implement. Measured: without this, `PayabliSecureStorage can only be accessed
// from within the same library group (referenced groupId=io.github.payabli from groupId=PayabliSDK)`.
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
