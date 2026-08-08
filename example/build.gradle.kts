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
fun demoSetting(
    key: String,
    default: String,
): String = providers.gradleProperty(key).orNull ?: demoSecrets.getProperty(key) ?: default

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
        // for two addresses instead of for everything. The config is ignored below 24, so a 23 build
        // would permit cleartext outright. Nothing published moves: :core and the capability modules
        // stay at 23 and an integrator can still target it.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEMO_ENTRY_POINT", quoted(demoSetting("payabli.demo.entryPoint", "")))
        buildConfigField("String", "DEMO_APP_ID", quoted(demoSetting("payabli.demo.appId", "")))
        buildConfigField(
            "String",
            "DEMO_SIGNING_CERTIFICATE",
            quoted(demoSetting("payabli.demo.signingCertificate", "")),
        )
        // Blank means resolve per run: emulator to 10.0.2.2, device to 127.0.0.1 over adb reverse.
        buildConfigField("String", "DEMO_TOKEN_HOST", quoted(demoSetting("payabli.demo.tokenHost", "")))
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
