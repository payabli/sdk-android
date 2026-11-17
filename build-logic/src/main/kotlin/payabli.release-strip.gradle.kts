import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.payabli.buildlogic.ReleaseStripExtension
import com.payabli.buildlogic.StripReleaseClasses
import com.payabli.buildlogic.VerifyReleaseStrip

// A member annotated `@StrippedFromRelease` is in the debug classes the tests run against and absent, with the
// lambdas and coroutine classes it encloses, from the release AAR. `internal` is public on the JVM, and
// `@JvmSynthetic` hides a member from Java source but not from reflection.

val releaseStrip = extensions.create<ReleaseStripExtension>("releaseStrip")
val androidComponents = extensions.getByType(LibraryAndroidComponentsExtension::class.java)
val release = androidComponents.selector().withBuildType("release")

// The release classes are the stripped ones, so a test calling a stripped member cannot compile against
// them. The debug unit tests run the same sources.
androidComponents.beforeVariants(release) { variant ->
    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
}

androidComponents.onVariants(release) { variant ->
    val suffix = variant.name.replaceFirstChar(Char::uppercase)
    val strip = tasks.register<StripReleaseClasses>("strip${suffix}Classes")
    variant.artifacts
        .forScope(ScopedArtifacts.Scope.PROJECT)
        .use(strip)
        .toTransform(
            ScopedArtifact.CLASSES,
            StripReleaseClasses::jars,
            StripReleaseClasses::directories,
            StripReleaseClasses::output,
        )

    val verify =
        tasks.register<VerifyReleaseStrip>("verify${suffix}Strip") {
            group = "verification"
            description = "Checks the $suffix AAR carries no member marked @StrippedFromRelease."
            aar.set(variant.artifacts.get(SingleArtifact.AAR))
            absent.set(releaseStrip.absent)
        }

    // Every task that hands the AAR on waits for the check, and the task that writes it is followed by it.
    tasks.matching { it.name == "check" || it.name == "assemble$suffix" || it.name.startsWith("publish") }
        .configureEach { dependsOn(verify) }
    tasks.matching { it.name == "bundle${suffix}Aar" }.configureEach { finalizedBy(verify) }
}
