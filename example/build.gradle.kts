import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("payabli.quality")
}

// `entryPoint` becomes PAYABLI_LIVETEST_ENTRY_POINT.
fun liveTestVariable(name: String): String =
    "PAYABLI_LIVETEST_" + name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

// Per-developer demo settings, read from a gitignored secrets.properties with committed defaults in
// secrets.properties.example. Nothing here is a credential: the entry point and the app id are
// identifiers, and the access token is minted at runtime by example-server.
//
// providers.fileContents is tracked, so editing secrets.properties re-runs configuration. A plain
// File.readText is not, and the configuration cache would serve the old values.
val demoSecrets: Properties =
    providers
        .fileContents(layout.projectDirectory.file("secrets.properties"))
        .asText
        .map { text -> Properties().apply { load(text.reader()) } }
        .getOrElse(Properties())

// -Ppayabli.demo.* wins over the file, so a one-off run needs no edit.
//
// A `key=` line with nothing after it reads back as "", not as missing, and the template ships those
// lines. Without isNotBlank the empty value would win over the default.
fun demoSetting(
    key: String,
    default: String,
): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: demoSecrets.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: default

// buildConfigField emits its value into generated Java verbatim, so an unescaped quote or backslash
// produces a source file that does not compile, reporting the generated file rather than the setting
// that broke it.
fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.payabli.example.app"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId = "com.payabli.example.app"
        // 24. The debug build reaches the local token server over cleartext, and the network
        // security config that permits it for two addresses is ignored below 24.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEMO_ENTRY_POINT", quoted(demoSetting("payabli.demo.entryPoint", "")))
        buildConfigField("String", "DEMO_APP_ID", quoted(demoSetting("payabli.demo.appId", "")))
        // Unvalidated. Which labels exist is DemoEnvironment's to say, and a copy of the list here
        // would be a second place to change and the one nothing compiles against.
        buildConfigField("String", "DEMO_ENVIRONMENT", quoted(demoSetting("payabli.demo.environment", "sandbox")))
        buildConfigField(
            "String",
            "DEMO_SIGNING_CERTIFICATE",
            quoted(demoSetting("payabli.demo.signingCertificate", "")),
        )
        // Blank means resolve per run: emulator to 10.0.2.2, device to 127.0.0.1 over adb reverse.
        buildConfigField("String", "DEMO_TOKEN_HOST", quoted(demoSetting("payabli.demo.tokenHost", "")))
        // 10.0.2.2 is the emulator's alias for the host machine's loopback; 127.0.0.1 is the
        // device's own, which adb reverse forwards.
        buildConfigField(
            "String",
            "DEMO_EMULATOR_TOKEN_HOST",
            quoted(demoSetting("payabli.demo.emulatorTokenHost", "10.0.2.2")),
        )
        buildConfigField(
            "String",
            "DEMO_DEVICE_TOKEN_HOST",
            quoted(demoSetting("payabli.demo.deviceTokenHost", "127.0.0.1")),
        )
        buildConfigField("int", "DEMO_TOKEN_PORT", demoSetting("payabli.demo.tokenPort", "8787"))
        buildConfigField("boolean", "DEMO_DIAGNOSTICS", demoSetting("payabli.demo.diagnostics", "true"))
        // Off unless asked for, and the button it enables is drawn in a debug build only.
        buildConfigField("boolean", "DEMO_PREFILL", demoSetting("payabli.demo.prefill", "false"))

        // The walkthrough submits real payments through the form, so it is kept out of an ordinary run and
        // excluded by name rather than skipped: a standing skip cannot be told apart from a regression that
        // started skipping. It also needs a reachable token server and a configured paypoint, which is why
        // asking for it is a deliberate flag and not a default.
        //
        //   ./gradlew :example:connectedDebugAndroidTest -Ppayabli.qaWalkthrough=true \
        //     -Ppayabli.demo.prefill=true -Ppayabli.demo.environment=qa -Ppayabli.demo.entryPoint=<entry>
        val walkthrough = "com.payabli.example.app.QaWalkthroughTest"
        val excluded = mutableListOf<String>()

        if (providers.gradleProperty("payabli.qaWalkthrough").orNull != "true") {
            excluded += walkthrough
        } else {
            // Asking for the walkthrough narrows the run to it, rather than adding it to the others.
            //
            // `NavigationSmokeTest` and `PayInSessionSourceInstrumentedTest` point the app at a fake token
            // server on a random port and pin `InstrumentedSession.ENTRY_POINT`, and both writes are
            // process-wide with no way back. A walkthrough sharing that process talks to a closed port and a
            // paypoint that does not exist, so its first step never passes and every flow times out waiting
            // for a form that stayed locked. Measured: 3 of 3 flows failed that way in a whole-suite run and
            // all 3 passed in an invocation of their own.
            testInstrumentationRunnerArguments["class"] = walkthrough

            // The same four `:payin`'s live tier takes, and the same names, because one set of secrets feeds
            // both. Given them, the walkthrough serves its own token and needs no server beside it; without
            // them it runs against whatever the build configured and a token server on the bench.
            //
            // All four or none, refused here as well as in the test. Forwarding a subset is what makes the
            // difference invisible: the run looks configured, takes the bench path anyway, and fails several
            // steps later on a form that never unlocked.
            //
            // Each also reads PAYABLI_LIVETEST_<NAME>. A -P value is an argument, so it lands in the process
            // command line; an environment variable does not.
            val liveTest =
                listOf("environment", "entryPoint", "clientId", "clientSecret")
                    .associateWith { name ->
                        providers.gradleProperty("payabli.liveTest.$name").orNull
                            ?: providers.environmentVariable(liveTestVariable(name)).orNull
                    }
            val missing = liveTest.filterValues { it == null }.keys
            if (missing.isNotEmpty() && missing.size < liveTest.size) {
                error("payabli.liveTest.* is partly set. Missing: ${missing.sorted().joinToString()}")
            }
            liveTest.forEach { (name, value) ->
                value?.let { testInstrumentationRunnerArguments["liveTest.$name"] = it }
            }
        }

        // One list, because `notClass` is a single runner argument: setting it twice keeps the last write and
        // silently readmits whatever the earlier one excluded.
        if (excluded.isNotEmpty()) {
            testInstrumentationRunnerArguments["notClass"] = excluded.joinToString(",")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AGP defaults this off, and the demo settings above are read through BuildConfig.
        buildConfig = true
    }
}

dependencies {
    // The SDK's payment form. An integrator would take the umbrella; this app takes the module
    // directly because it is in the same build.
    implementation(project(":payin"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    // androidx.test.ext:junit does not bring the runner transitively, and without it the test APK
    // dies with ClassNotFoundException before any test runs.
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
