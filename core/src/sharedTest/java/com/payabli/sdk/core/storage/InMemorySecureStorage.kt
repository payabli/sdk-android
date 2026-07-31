package com.payabli.sdk.core.storage

/**
 * A [PayabliSecureStorage] that keeps values in a map, for testing a consumer without a Keystore.
 *
 * The counterpart of iOS's `InMemorySecureStorage`. It lives in `sharedTest` because Android has no
 * fixtures module yet and both source sets need it.
 *
 * Copies on the way in and out, so the map never aliases a caller's array: a consumer that wipes what it
 * passed to [set], as it should, would otherwise blank the stored value too. The map was briefly a constructor
 * parameter, which defeated exactly that: a caller seeding the fixture kept references to the stored arrays and
 * could wipe them afterwards. Nothing seeded it, so the parameter went rather than gaining a copy.
 *
 * **Not encrypted, and that is the point.** Never construct one outside a test.
 *
 * It enforces the same key rule as the shipping store, through the shared [requireRepresentableKey]. A fixture
 * that accepts what production rejects lets a consumer's test pass here and fail in production, which is the one
 * thing a fixture must not do.
 *
 * Deliberately just the three contract methods. A write counter and a text `snapshot()` were here for a consumer
 * that does not exist yet, and the snapshot was actively wrong: decoding stored bytes as UTF-8 put back the text
 * assumption the contract dropped, so a test asserting on it would have mangled any value that is not valid
 * UTF-8. Whatever P3.2 actually needs it can add, against a real call site.
 *
 * `@VisibleForTesting` is not used, and would not fit: `sharedTest` compiles only into `test` and `androidTest`,
 * so nothing here has production visibility to widen. The same reasoning as `PayabliTransports`.
 */
internal class InMemorySecureStorage : PayabliSecureStorage {
    private val values: MutableMap<String, ByteArray> = mutableMapOf()

    override suspend fun get(key: String): ByteArray? {
        requireRepresentableKey(key)
        return values[key]?.copyOf()
    }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        requireRepresentableKey(key)
        values[key] = value.copyOf()
    }

    override suspend fun remove(key: String) {
        requireRepresentableKey(key)
        values.remove(key)
    }
}
