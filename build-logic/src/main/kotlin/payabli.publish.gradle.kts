import com.android.build.api.dsl.LibraryExtension
import com.payabli.buildlogic.extraEnvironmentsSetting
import com.payabli.buildlogic.refusePublishingToARemoteRepository
import com.payabli.buildlogic.refusePublishingWithExtraEnvironments
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// Convention plugin: shared Maven publishing for every publishable Payabli SDK
// module. Applied via `id("payabli.publish")`. Adapts to the module type:
//   - com.android.library  -> publishes the "release" AAR (+ sources + javadoc)
//   - java-platform (BOM)  -> publishes the platform POM
// The whole family releases under the sdk-android-* coordinate scheme. Library
// modules are named bare (:core, :payin, ...) and default to sdk-android-<name>;
// the two aggregate modules keep descriptive names (:payabli-android, :payabli-bom)
// and set their exact published artifactId via extra["payabliArtifactId"]
// (-> sdk-android and sdk-android-bom). group/version come from gradle.properties
// so all artifacts share one Maven group (required for @RestrictTo(LIBRARY_GROUP)).

// Shared POM metadata (esp. the license). Applied to every publication so each
// artifact declares the PayabliSDK Commercial License in its Maven metadata.
// Third-party attribution lives in THIRD-PARTY-NOTICES.md, not here: a POM <license>
// states this artifact's own license only.
fun MavenPublication.applyPayabliPom() = pom {
    name.set(artifactId)
    description.set("Payabli Android SDK — $artifactId")
    url.set("https://github.com/payabli/sdk-android")
    licenses {
        license {
            name.set("PayabliSDK Commercial License")
            url.set("https://github.com/payabli/sdk-android/blob/main/LICENSE")
            distribution.set("repo")
        }
    }
}

plugins {
    `maven-publish`
    // Supply-chain SBOM: every publishable module emits a CycloneDX bill of
    // materials (build/reports/cyclonedx/bom.json + bom.xml) via the `cyclonedxBom` task.
    id("org.cyclonedx.bom")
}

group = providers.gradleProperty("payabli.group").get()
version = providers.gradleProperty("payabli.version").get()

// Published artifactId: library modules (bare names) release as sdk-android-<name>;
// a module may override with extra["payabliArtifactId"] (used by the aggregates to
// publish as "sdk-android" and "sdk-android-bom"). Read lazily so the module's
// opt-in in its own build script is visible.
fun Project.publishedArtifactId(): String =
    (findProperty("payabliArtifactId") as String?) ?: "sdk-android-$name"

publishing {
    repositories {
        // A directory, not a server. Publishing sends this tree separately, so Gradle's job ends at
        // writing a correct layout on disk and nothing here uploads.
        maven {
            name = "Staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// At task time, not configuration time: a configuration-time check passes and a later afterEvaluate can
// still turn the repository remote. The guard and its message are in PublishRepositories.kt.
tasks.refusePublishingToARemoteRepository()

// A published artifact carries only the environments committed in PayabliEnvironment; the guard and its
// reasoning are in ExtraEnvironments.kt.
tasks.refusePublishingWithExtraEnvironments(extraEnvironmentsSetting(providers))

// The plugin generates on demand and attaches to nothing.
fun MavenPublication.attachCycloneDxSbom(project: Project) {
    val sbom = project.tasks.named("cyclonedxBom")
    val reports = project.layout.buildDirectory.dir("reports/cyclonedx")
    listOf("json", "xml").forEach { format ->
        artifact(reports.map { it.file("bom.$format") }) {
            classifier = "cyclonedx"
            extension = format
            builtBy(sbom)
        }
    }
}

// Android library modules -> "release" component.
pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        publishing {
            singleVariant("release") {
                withSourcesJar()
                withJavadocJar()
            }
        }

        defaultConfig {
            aarMetadata {
                minCompileSdk = 31
            }
        }
    }
    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("release") {
                    from(components["release"])
                    artifactId = publishedArtifactId()
                    applyPayabliPom()
                    attachCycloneDxSbom(project)
                }
            }
        }
    }
}

// Version BOM modules -> java-platform component.
pluginManager.withPlugin("java-platform") {
    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("bom") {
                    from(components["javaPlatform"])
                    artifactId = publishedArtifactId()
                    applyPayabliPom()
                    attachCycloneDxSbom(project)
                }
            }
        }
    }
}
