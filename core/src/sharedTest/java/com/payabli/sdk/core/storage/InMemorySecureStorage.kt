package com.payabli.sdk.core.storage

/**
 * A [PayabliSecureStorage] that keeps values in a map, for testing a consumer without a Keystore.
 *
 * The counterpart of iOS's `InMemorySecureStorage`, which ships in `PayabliSDKTestUtils`. Here it lives
 * in `sharedTest` because Android has no fixtures module yet (PLA-2192), and because both source sets
 * need it: JVM tests for consumers, instrumented tests for comparison against the real thing.
 *
 * **Not encrypted, and that is the point.** It exists so a test can assert what a consumer stores and
 * retrieves. Never construct one outside a test.
 */
internal class InMemorySecureStorage(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : PayabliSecureStorage {
    /** Counts writes, so a test can show a consumer persisted once rather than on every call. */
    var writes: Int = 0
        private set

    override suspend fun get(key: String): String? = values[key]

    override suspend fun set(
        key: String,
        value: String,
    ) {
        writes++
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    /** The stored values, for asserting on what a consumer wrote. */
    fun snapshot(): Map<String, String> = values.toMap()
}
