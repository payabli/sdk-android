package com.payabli.sdk.core.storage

/**
 * The key rule, in one place because two implementations have to agree on it.
 *
 * A key must survive a UTF-8 round trip. The name is what binds a blob to its entry, as GCM AAD, and
 * `String.toByteArray` replaces malformed UTF-16 rather than refusing it: measured, `"\uD800"`, `"\uD801"` and a
 * literal `"?"` all encode to the single byte `0x3f`. Entries whose names collapse can therefore open each other's
 * value with the tag check passing, which is the substitution attack the binding exists to prevent, reached by
 * naming instead of by editing the file. The same string is also the persisted map key, so a name that cannot
 * round-trip makes an entry ambiguous in two places at once.
 *
 * Shared rather than private to the file-backed implementation, because [InMemorySecureStorage] enforcing less
 * than the shipping store means a consumer's test passes against the fixture and fails in production. That is the
 * one thing a fixture must not do.
 *
 * `require`, not a [SecureStorageException]: a key is a plaintext name chosen by calling code, so one that is not
 * representable is a caller bug rather than a storage outcome. The check asserts the property directly, that the
 * name means the same thing after being written and read back, rather than configuring an encoder to report.
 */
internal fun requireRepresentableKey(key: String) {
    require(String(key.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == key) {
        "a storage key must be representable as UTF-8 without loss"
    }
}
