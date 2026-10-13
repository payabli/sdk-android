// Convention plugin: the checked-in API signature over a module's public surface, applied via
// `id("payabli.api-surface")`. The file `api/release.txt` under the module records the release
// variant's public declarations, unfiltered: annotation-based hiding is deliberately not
// configured, so a declaration annotated for sibling modules stays in the record.

plugins {
    id("me.tylerbwong.gradle.metalava")
}

metalava {
    // The signature task runs from CI by name, never from a task lifecycle, so building,
    // assembling and publishing cannot depend on the signature being current.
    enforceCheck.set(false)
    // One file records the release variant, which is the artifact a published module ships. The
    // debug variant's generated BuildConfig constants differ, so a second file would record the
    // same surface with different values rather than a different surface.
    filename.set("api/release.txt")
    // The metalava CLI version, pinned so a plugin upgrade cannot change the tool that formatted
    // the checked-in file. Moving this pin regenerates api/release.txt in the same change.
    version.set("1.0.0-alpha15")
}

// The debug tasks would write debug-flavoured constants into the release record (generate) or
// compare them against it (check), so they are disabled rather than left to fail confusingly.
tasks.matching {
    it.name == "metalavaGenerateSignatureDebug" || it.name == "metalavaCheckCompatibilityDebug"
}.configureEach {
    enabled = false
}
