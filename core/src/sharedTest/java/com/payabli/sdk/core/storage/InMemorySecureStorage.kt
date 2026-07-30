package com.payabli.sdk.core.storage

/**
 * A [PayabliSecureStorage] that keeps values in a map, for testing a consumer without a Keystore.
 *
 * The counterpart of iOS's `InMemorySecureStorage`. It lives in `sharedTest` because Android has no
 * fixtures module yet and both source sets need it.
 *
 * Copies on the way in and out, so the map never aliases a caller's array: a consumer that wipes what it
 * passed to [set], as it should, would otherwise blank the stored value too.
 *
 * **Not encrypted, and that is the point.** Never construct one outside a test.
 */
internal class InMemorySecureStorage(
    private val values: MutableMap<String, CharArray> = mutableMapOf(),
) : PayabliSecureStorage {
    /** Counts writes, so a test can show a consumer persisted once rather than on every call. */
    var writes: Int = 0
        private set

    override suspend fun get(key: String): CharArray? = values[key]?.copyOf()

    override suspend fun set(
        key: String,
        value: CharArray,
    ) {
        writes++
        values[key] = value.copyOf()
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    /** The stored values as text, for asserting on what a consumer wrote. */
    fun snapshot(): Map<String, String> = values.mapValues { String(it.value) }
}
