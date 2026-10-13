import org.cyclonedx.gradle.CyclonedxDirectTask

// Convention plugin: the checked-in API signature over a module's public surface, applied via
// `id("payabli.api-surface")`. The file `api/release.txt` under the module records the release
// variant's public declarations, unfiltered, so declarations annotated for sibling modules stay.

plugins {
    id("me.tylerbwong.gradle.metalava")
}

// The metalava plugin resolves its tool through a project configuration, and a CycloneDX scan
// covers every resolvable configuration on the project, so the signature tooling would otherwise
// appear in a published module's bill of materials as though the artifact depended on it.
plugins.withId("org.cyclonedx.bom") {
    tasks.named("cyclonedxDirectBom", CyclonedxDirectTask::class.java) {
        skipConfigs = listOf("metalava")
    }
}

metalava {
    // The signature task runs from CI by name, never from a task lifecycle, so building,
    // assembling and publishing cannot depend on the signature being current.
    enforceCheck.set(false)
    // One file records the release variant, which is the artifact a published module ships. The
    // debug variant's generated BuildConfig constants differ, so a second file would record the
    // same surface with different values.
    filename.set("api/release.txt")
    // The metalava CLI version, pinned so a plugin upgrade cannot change the tool that formatted
    // the checked-in file. Moving this pin regenerates api/release.txt in the same change.
    version.set("1.0.0-alpha15")
}

// The debug tasks would write debug-flavored constants into the release record (generate) or
// compare them against it (check), so both are disabled.
tasks.matching {
    it.name == "metalavaGenerateSignatureDebug" || it.name == "metalavaCheckCompatibilityDebug"
}.configureEach {
    enabled = false
}
