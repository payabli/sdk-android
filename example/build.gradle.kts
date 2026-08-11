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
// providers.fileContents rather than File.readText, because the configuration cache is on for the
// whole repository and a raw file read at configuration time is not a tracked input. Editing
// secrets.properties would not invalidate the cache, and the build would keep using stale values
// without saying so. This provider is tracked, so an edit re-runs configuration.
val demoSecrets: Properties =
    providers
        .fileContents(layout.projectDirectory.file("secrets.properties"))
        .asText
        .map { text -> Properties().apply { load(text.reader()) } }
        .getOrElse(Properties())

// -Ppayabli.demo.* wins over the file, so a one-off run needs no edit.
//
// Blank counts as absent, which is not what either source hands back. `Properties.getProperty`
// returns "" for a `key=` line rather than null, and the template ships exactly those lines, so a
// blank overrode the default instead of falling through to it. A String field then carried "" and
// resolved an address of "http://:8787"; an int or boolean field emitted `= ;` into generated Java
// and the module stopped compiling for anyone who followed the README and copied the template.
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
        // 24, one above the SDK modules' floor of 23, because the debug build talks to the local
        // token server over cleartext and a network security config is the only way to permit that
        // for two addresses instead of for everything.
        //
        // API 23 has no such config, so the only lever there is the usesCleartextTraffic flag, which
        // is all-or-nothing. Both manifests declare it false, so a 23 build would block the local
        // token server; declaring it true to unblock it would permit cleartext to every host the app
        // can reach. Neither is what this app wants, and 24 is where the choice stops being between
        // those two.
        //
        // Nothing published moves: :core and the capability modules stay at 23 and an integrator can
        // still target it.
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
        // The two addresses the local token server is reached at when nothing overrides it. Set
        // here rather than in Kotlin because they are deployment values: an emulator that maps the
        // host differently, or a device forwarded on another port, is a settings change and not a
        // code change. The defaults are the standard ones and hold for everyone who changes
        // nothing.
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
    // testInstrumentationRunner names AndroidJUnitRunner, and androidx.test.ext:junit does not bring
    // it transitively. Without this the test APK installs and dies with ClassNotFoundException
    // before a single test runs, which is how the stale package assertion in ExampleInstrumentedTest
    // survived: nobody had ever got an instrumented test to start here.
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
