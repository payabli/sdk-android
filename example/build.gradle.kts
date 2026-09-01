import com.payabli.buildlogic.liveTestSettings
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("payabli.quality")
}

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

/**
 * The environment variable a setting also answers to: `payabli.demo.entryPoint` is `PAYABLI_DEMO_ENTRYPOINT`.
 *
 * Derived rather than listed, so a setting added later has one without anybody remembering to add it.
 */
fun envVarFor(key: String): String = key.replace('.', '_').uppercase()

// Three sources, most specific first: -Ppayabli.demo.* for a one-off run, the environment for a shell or a
// CI job that has no file to edit, and secrets.properties for a developer's own machine.
//
// A `key=` line with nothing after it reads back as "", not as missing, and the template ships those
// lines. Without isNotBlank the empty value would win over the default.
fun demoSetting(
    key: String,
    default: String,
): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envVarFor(key)).orNull?.takeIf { it.isNotBlank() }
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
        // 30, imposed by the card reader this app links. The floor also has to stay at or above 24,
        // which is where the network security config permitting cleartext to the local token server
        // stops being ignored.
        //
        // The card reader's native library is arm64 only, and the app still installs on a 32-bit-only
        // handset because another dependency carries an armeabi-v7a one. There, the Tap to pay screen
        // is the one part that cannot work.
        minSdk = 30
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
        // The Google Cloud project the Play Integrity API is enabled in, which the card-present screen
        // needs and a hand-installed build cannot infer. Not a secret: every app shipping Play Integrity
        // carries its project number. Blank leaves the Tap to pay screen reporting that it is unset.
        buildConfigField(
            "String",
            "DEMO_CLOUD_PROJECT_NUMBER",
            quoted(demoSetting("payabli.cloudProjectNumber", "")),
        )

        // The walkthrough submits real payments through the form, so it is kept out of an ordinary run and
        // excluded by name rather than skipped: a standing skip cannot be told apart from a regression that
        // started skipping. It also needs a reachable token server and a configured paypoint, which is why
        // asking for it is a deliberate flag and not a default.
        //
        //   ./gradlew :example:connectedDebugAndroidTest -Ppayabli.sampleWalkthrough=true \
        //     -Ppayabli.demo.prefill=true -Ppayabli.demo.environment=sandbox -Ppayabli.demo.entryPoint=<entry>
        val walkthrough = "com.payabli.example.app.SampleWalkthroughTest"
        val excluded = mutableListOf<String>()

        if (providers.gradleProperty("payabli.sampleWalkthrough").orNull != "true") {
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

            // The same three `:payin`'s live tier takes, and the same names, because one run configures both.
            // None is a credential: a token server is reachable at `tokenHost` and holds the client id and
            // secret, and the app asks it for a token exactly as it would ask an integrator's backend.
            //
            // All three or none, refused here as well as in the test. Forwarding a subset is what makes the
            // difference invisible: the run looks configured, points the app at whatever the build compiled
            // in, and fails several steps later on a form that never unlocked.
            liveTestSettings(providers)?.forEach { (name, value) ->
                testInstrumentationRunnerArguments["liveTest.$name"] = value
            }
        }

        // One list, because `notClass` is a single runner argument: setting it twice keeps the last write and
        // silently readmits whatever the earlier one excluded.
        if (excluded.isNotEmpty()) {
            testInstrumentationRunnerArguments["notClass"] = excluded.joinToString(",")
        }
    }

    // Two builds of the sample app, differing in one dependency and nothing else.
    //
    // **Linking the telemetry artifact is the whole of the integration**, so the only honest test of an app
    // that did not link it is an app that did not link it. Simulating the absence from inside a test can only
    // reach the code path; it cannot show that the SDK initializes, runs and reports nothing when the class
    // is genuinely not on the classpath, which is what every integrator who depends on `:core` alone gets.
    //
    // `withTelemetry` is what the umbrella gives an integrator and is the default for ordinary runs.
    flavorDimensions += "reporting"
    productFlavors {
        create("withTelemetry") {
            dimension = "reporting"
        }
        create("withoutTelemetry") {
            dimension = "reporting"

            // This build exists to answer one question — does the SDK work when the artifact is not linked —
            // so it runs the one class that asks it. Running the whole instrumented suite twice would double
            // a device run to re-prove things the other flavor already proved, and the walkthrough talks to
            // real paypoints.
            testInstrumentationRunnerArguments["class"] =
                "com.payabli.example.TelemetryLinkageInstrumentedTest"
        }
    }

    // **The release variant exists for one reason: the card reader vendor signs a release artifact.**
    // It was disabled here, on the grounds that nothing publishes this app and so nobody could say why CI
    // would assemble it. That reason has been overtaken: the vendor's signing and onboarding step is what
    // an APK has to pass before the reader will arm on any device, and it expects a release build. No CI
    // job assembles it, so the objection it was disabled under does not return.
    //
    // Signed with the debug keystore, deliberately and only until the vendor's process replaces that
    // signature. A release variant with no signing config produces an unsigned APK, which cannot be
    // installed and so cannot be retested against the very failure this is for. The certificate is the one
    // already shared with the vendor, so what is submitted matches what they were told to expect.
    //
    // The minified build worth checking is still an integrator's, against the published artifacts and
    // their own keep rules, and is not this.
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The card reader's kernel is refused when its native library is page-mapped from the APK rather
    // than unpacked at install: Fiserv's signing step rejects the artifact outright. This is what sets
    // `android:extractNativeLibs="true"` on the merged manifest, and it has to be set by the module that
    // builds the APK. Declaring it in :taptopay's manifest does nothing, measured: AGP resolves the
    // attribute at packaging time from this value and overwrote a library-supplied `true` with `false`.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

    // The request drain, as `:payin` shares its fixtures. A fake server uses it on a device and its own tests
    // run on the JVM, so it is compiled into both. Nothing here may be Android-only.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
    }
}

dependencies {
    // The SDK's payment form. An integrator would take the umbrella; this app takes the module
    // directly because it is in the same build.
    implementation(project(":payin"))
    // Only one flavor links it; `withoutTelemetry` is the build that proves the SDK works without it.
    "withTelemetryImplementation"(project(":telemetry"))

    // The SDK's card-present module, which the Tap to pay screen drives. It resolves from a credentialed
    // repository, so every job that builds this app needs GPR_TOKEN; ci.yml puts it beside :taptopay for
    // that reason.
    implementation(project(":taptopay"))

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
