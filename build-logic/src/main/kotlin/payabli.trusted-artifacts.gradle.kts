// Convention plugin: `verifyTrustedArtifactNames` fails if anything this project or its build script
// resolves is a file that gradle/verification-metadata.xml trusts without checking. See
// VerifyTrustedArtifactNames.

val verifyTrustedArtifactNames = tasks.register<VerifyTrustedArtifactNames>("verifyTrustedArtifactNames") {
    group = "verification"
    description = "Fails if a resolved file is one dependency verification trusts without checking."
    // Lenient, so a configuration that cannot resolve here is skipped rather than failing this task. External
    // modules only: a project's own output is never verified, and taking it would run its producer tasks first.
    (configurations + buildscript.configurations)
        .filter { it.isCanBeResolved }
        .forEach { configuration ->
            resolved.from(
                configuration.incoming.artifactView {
                    isLenient = true
                    componentFilter { it is ModuleComponentIdentifier }
                }.files,
            )
        }
}

plugins.withId("base") {
    tasks.named("check") { dependsOn(verifyTrustedArtifactNames) }
}
