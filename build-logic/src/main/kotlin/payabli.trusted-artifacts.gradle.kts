// Convention plugin: `verifyTrustedArtifactNames` fails if anything this project or its build script
// resolves is a file that gradle/verification-metadata.xml trusts without checking. See
// VerifyTrustedArtifactNames.

val verifyTrustedArtifactNames = tasks.register<VerifyTrustedArtifactNames>("verifyTrustedArtifactNames") {
    group = "verification"
    description = "Fails if a resolved file is one dependency verification trusts without checking."
    // Lenient, so a configuration that cannot resolve here is skipped rather than failing this task.
    (configurations + buildscript.configurations)
        .filter { it.isCanBeResolved }
        .forEach { configuration -> resolved.from(configuration.incoming.artifactView { isLenient = true }.files) }
}

plugins.withId("base") {
    tasks.named("check") { dependsOn(verifyTrustedArtifactNames) }
}
