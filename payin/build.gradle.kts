import com.payabli.buildlogic.liveTestSettings
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

        // The live tier, which sends real requests to a real environment. Configured, its settings reach the
        // test as runner arguments; absent, the class is excluded by name so the run reports no skip, which is
        // how `:taptopay` gates its real Play Integrity test and for the same reason: a standing skip cannot
        // be told apart from a regression that started skipping.
        //
        // A token server has to be reachable at `tokenHost`, because nothing here mints one: the client
        // credential belongs to that server and never to the device. `example-server` is one.
        //
        // Set these in ~/.gradle/gradle.properties, never here, and one environment per invocation because the
        // SDK installs one session per process:
        //
        //   ./gradlew :payin:connectedDebugAndroidTest \
        //     -Ppayabli.liveTest.environment=sandbox -Ppayabli.liveTest.entryPoint=<entry> \
        //     -Ppayabli.liveTest.tokenHost=<host:port the token server is listening on>
        //
        // 10.0.2.2 is where an emulator reaches the machine running it, and 127.0.0.1 where a handset reaches
        // it once the port is forwarded. Which port depends on what the server was started with.
        //
        // Each also reads an environment variable, which is what an automated run sets. Both halves live in
        // build-logic, because `:example` resolves the same three.
        val liveFlows = "com.payabli.sdk.payin.payment.PayInLiveFlowsInstrumentedTest"
        val liveTest = liveTestSettings(providers)

        // Every class or method kept out of an ordinary run, as one list, because `notClass` is a single
        // runner argument: setting it twice keeps the last write and silently readmits whatever the earlier
        // one excluded. A -P runner argument would do the same to all of it from outside, so nothing in CI
        // passes `notClass`; the annotation tiers travel under `notAnnotation`, which is a different key.
        val excluded = mutableListOf<String>()

        // Quarantined, not skipped: intermittent on hardware and green everywhere else, so it fails a run
        // that has nothing wrong with it. Measured before quarantining, one failure in four full-suite runs
        // on one handset against none in eight emulator runs, at the first interaction of the first test in
        // the class with "No compose hierarchies found in the app". No mechanism was established and the
        // obvious reading is wrong: the sibling classes in this package interact immediately after
        // setContent too, so a missing wait is not what separates them. It comes back when a reproduction
        // does, and it runs meanwhile with the property below.
        //
        // Named by class as well, because the property alone readmits it to a whole-module run: a developer
        // who has stored the live settings above would send real transactions while rechecking a form test.
        // The class filter keeps the run to the four tests being rechecked.
        //
        //   ./gradlew :payin:connectedAndroidTest -Ppayabli.quarantine.run=true \
        //     -Pandroid.testInstrumentationRunnerArguments.class=com.payabli.sdk.payin.ui.PayInFormOutcomeAcrossRecreationInstrumentedTest
        if (providers.gradleProperty("payabli.quarantine.run").orNull != "true") {
            excluded += "com.payabli.sdk.payin.ui.PayInFormOutcomeAcrossRecreationInstrumentedTest"
        }

        if (liveTest != null) {
            liveTest.forEach { (name, value) -> testInstrumentationRunnerArguments["liveTest.$name"] = value }
        } else {
            excluded += liveFlows
        }

        if (excluded.isNotEmpty()) {
            testInstrumentationRunnerArguments["notClass"] = excluded.joinToString(",")
        }
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

    testImplementation(project(":testutils"))
    androidTestImplementation(project(":testutils"))
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
