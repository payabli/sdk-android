package com.payabli.sdk.core.storage

import androidx.annotation.RestrictTo

/**
 * The key rule, in one place because every implementation has to agree on it.
 *
 * A key must survive a UTF-8 round trip. The name is what binds a blob to its entry, as GCM AAD, and
 * `String.toByteArray` replaces malformed UTF-16 rather than refusing it: `"\uD800"`, `"\uD801"` and a
 * literal `"?"` all encode to the single byte `0x3f`. Entries whose names collapse can therefore open each other's
 * value with the tag check passing, which is the substitution attack the binding exists to prevent, reached by
 * naming instead of by editing the file. The same string is also the persisted map key, so a name that cannot
 * round-trip makes an entry ambiguous in two places at once.
 *
 * Carried at the same visibility as [PayabliSecureStorage] itself, because an implementation of that interface
 * that enforces less than the shipping store lets a caller's test pass against it and fail in production. The
 * rule belongs wherever the interface can be implemented, which is anywhere in this group.
 *
 * `require`, not a [SecureStorageException]: a key is a plaintext name chosen by calling code, so one that is not
 * representable is a caller bug rather than a storage outcome. The check asserts the property directly, that the
 * name means the same thing after being written and read back, rather than configuring an encoder to report.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun requireRepresentableKey(key: String) {
    require(String(key.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == key) {
        "a storage key must be representable as UTF-8 without loss"
    }
}
