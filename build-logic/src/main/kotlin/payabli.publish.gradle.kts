import com.android.build.api.dsl.LibraryExtension
import com.payabli.buildlogic.extraEnvironmentsSetting
import com.payabli.buildlogic.refusePublishingWithExtraEnvironments
import com.payabli.buildlogic.refusePublishingWithNoticePlaceholder
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

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

// Shared POM metadata. Applied to every publication so each artifact declares the
// PayabliSDK Commercial License in its Maven metadata.
// Third-party attribution lives in THIRD-PARTY-NOTICES.md, not here: a POM <license>
// states this artifact's own license only.
//
// `developers` and `scm` are here because Maven Central requires them and rejects a
// bundle that omits either. They say nothing a reader of this repository does not
// already know, which is why they read as boilerplate and are not.
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
    developers {
        developer {
            id.set("payabli")
            name.set("Payabli")
            url.set("https://www.payabli.com")
        }
    }
    scm {
        url.set("https://github.com/payabli/sdk-android")
        connection.set("scm:git:https://github.com/payabli/sdk-android.git")
        developerConnection.set("scm:git:ssh://git@github.com/payabli/sdk-android.git")
    }
}

plugins {
    `maven-publish`
    signing
    // Supply-chain SBOM: every publishable module emits a CycloneDX bill of
    // materials (build/reports/bom.json + bom.xml) via the `cyclonedxBom` task.
    id("org.cyclonedx.bom")
}

group = providers.gradleProperty("payabli.group").get()

// The version a publish carries, resolved here rather than in a workflow.
//
// Putting this in CI would mean a local build could not reproduce an artifact CI
// produced, and the two would drift in exactly the place that is hardest to notice:
// the coordinates. So the workflows pass inputs and this decides, which is what lets
// `publishToMavenLocal -Ppayabli.snapshot=true` produce the same QA artifact the
// snapshot job does.
//
//   nothing set                    -> payabli.version, for an ordinary local build
//   -Ppayabli.snapshot=true        -> <payabli.version>-SNAPSHOT, a QA build
//   -Ppayabli.publishVersion=1.2.3 -> exactly that, the release workflow's tag
//
// publishVersion wins when both are passed: a release names its version outright and
// must not be turned into a snapshot by a stray property in a daemon's environment.
val baseVersion = providers.gradleProperty("payabli.version").get()
val requestedVersion = providers.gradleProperty("payabli.publishVersion").orNull?.trim()
val wantsSnapshot = providers.gradleProperty("payabli.snapshot").orNull?.trim() == "true"
version =
    when {
        !requestedVersion.isNullOrEmpty() -> requestedVersion
        wantsSnapshot -> "$baseVersion-SNAPSHOT"
        else -> baseVersion
    }

// Published artifactId: library modules (bare names) release as sdk-android-<name>;
// a module may override with extra["payabliArtifactId"] (used by the aggregates to
// publish as "sdk-android" and "sdk-android-bom"). Read lazily so the module's
// opt-in in its own build script is visible.
fun Project.publishedArtifactId(): String =
    (findProperty("payabliArtifactId") as String?) ?: "sdk-android-$name"

publishing {
    repositories {
        // Releases and release candidates. Immutable: a version published here cannot be replaced or
        // withdrawn, which is why the release workflow validates before it creates the tag rather than
        // after.
        //
        // The endpoint is the OSSRH-compatible staging API rather than the Portal's bundle upload, because
        // that is the one `maven-publish` can deploy to without a third-party plugin. **Confirm it against
        // Sonatype's current documentation when the namespace is claimed**: nothing here has been run, the
        // account does not exist yet, and this URL is the part most likely to have moved.
        maven {
            name = "MavenCentral"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME").orNull
                password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD").orNull
            }
        }

        // Internal QA snapshots. Inert until the bucket exists.
        //
        // Not GitHub Packages, which is what this replaced: `payabli/sdk-android` is a public repository
        // and a package published there carries an anonymously visible version list. Authentication gates
        // the download, not the listing, so internal build identifiers would be public.
        //
        // The layout starts at the bucket root, as `com/payabli/...`, so there is no prefix here. The
        // `/maven` prefix belongs to the CDN, where it separates the mirrored reader from the iOS `/spm`
        // objects sharing that origin; this bucket holds nothing else and needs no separator.
        //
        // AwsImAuthentication takes the credentials the OIDC role hands the job, so no long-lived key is
        // stored anywhere. A developer authenticated to AWS locally can publish here too; a developer who
        // is not gets a QA build from `publishToMavenLocal -Ppayabli.snapshot=true` instead.
        maven {
            name = "QaSnapshots"
            url = uri("s3://sdk-artifacts-qa")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// A published artifact carries only the environments committed in PayabliEnvironment; the guard and its
// reasoning are in ExtraEnvironments.kt.
tasks.refusePublishingWithExtraEnvironments(extraEnvironmentsSetting(providers))

// A module whose third-party notice still carries a placeholder does not leave this machine; the guard and
// its reasoning are in ThirdPartyNotice.kt. Named per module, so a module without one is unaffected.
tasks.refusePublishingWithNoticePlaceholder(
    layout.projectDirectory
        .file("src/main/resources/META-INF/${publishedArtifactId()}_THIRD-PARTY-NOTICES.txt")
        .asFile,
)

// Android library modules -> "release" component.
pluginManager.withPlugin("com.android.library") {
    // The license travels inside the artifact, not only as a URL in the POM. An integrator who has the AAR
    // and no network still has the terms they are bound by.
    //
    // Named per artifact rather than META-INF/LICENSE, which is the same reason this module's third-party
    // notice is: an app merging several AARs meets one file per artifact instead of a collision, and the
    // default packaging rules that drop or dedupe META-INF/LICENSE* leave these alone.
    // A plain directory, not the task's output provider: the Android source set API rejects a Provider
    // outright, because Studio cannot tell a generated tree from a hand-edited one. So the path is fixed
    // here and the ordering is stated separately, on `preBuild`, which everything else runs after.
    val licenseDir = layout.buildDirectory.dir("generated/payabliLicense").get().asFile
    // The name is resolved here and the two-argument `rename` takes strings. The single-argument form
    // takes a lambda, and a lambda written in a `.gradle.kts` captures the script object, which the
    // configuration cache cannot serialize; the same reason the guards in ExtraEnvironments.kt are
    // compiled Actions rather than lambdas.
    val licenseName = "${publishedArtifactId()}_LICENSE.txt"
    val copyLicense =
        tasks.register<Copy>("copyPayabliLicense") {
            from(rootProject.layout.projectDirectory.file("LICENSE"))
            into(File(licenseDir, "META-INF"))
            rename("LICENSE", licenseName)
        }
    tasks.named("preBuild") { dependsOn(copyLicense) }

    extensions.configure<LibraryExtension> {
        publishing {
            singleVariant("release") {
                withSourcesJar()
                withJavadocJar()
            }
        }
        sourceSets.getByName("main").resources.srcDir(licenseDir)
    }

    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("release") {
                    from(components["release"])
                    artifactId = publishedArtifactId()
                    applyPayabliPom()
                }
            }
        }
        signPayabliPublications()
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
                }
            }
        }
        signPayabliPublications()
    }
}

/**
 * Signs every publication when a key is available, and does nothing when one is not.
 *
 * Maven Central rejects an unsigned artifact, so the release workflow supplies the key. Everything else
 * has to keep working without it: a contributor running `publishToMavenLocal` has no signing key and
 * should not be asked for one, and requiring it would make the local QA build in the header impossible
 * on any machine but a release runner's.
 *
 * In memory rather than from a keyring file, because the key reaches CI as a secret and never as a path.
 */
fun Project.signPayabliPublications() {
    val key = providers.environmentVariable("PAYABLI_SIGNING_KEY").orNull
    if (key.isNullOrBlank()) return
    val password = providers.environmentVariable("PAYABLI_SIGNING_PASSWORD").orNull.orEmpty()
    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(key, password)
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}
