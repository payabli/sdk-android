package com.payabli.sdk.core.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * so nothing here has production visibility to widen. The same reasoning as `TransportFactory`.
 */
internal class InMemorySecureStorage(
    /**
     * Runs inside the critical section, so a test can hold the lock and prove a second caller cannot enter.
     *
     * A no-op by default, which leaves every other use unchanged. It exists because the lost-update test below is a
     * weak detector: measured, it catches a removed mutex about 1 run in 5, and raising the writer count does not
     * help, since the critical section is a single map assignment. Asserting exclusion directly is deterministic
     * where asserting its consequence is not.
     *
     * A hook here costs nothing that the earlier debate about a seam in `KeystoreValueCipher` cost. This file is in
     * `sharedTest`, compiled only into `test` and `androidTest`, so this is test-only control flow in test-only
     * code rather than in a class that owns key material.
     */
    private val insideCriticalSection: suspend () -> Unit = {},
) : PayabliSecureStorage {
    private val values: MutableMap<String, ByteArray> = mutableMapOf()

    /**
     * The same primitive the shipping store uses, for the same reason.
     *
     * Without it this map is mutated unguarded, so concurrent writers race and lose entries where the real store
     * serialises them. A fixture that is *less* safe than what it stands in for makes a consumer's concurrency test
     * flake for a reason production does not have, which is worse than not having the fixture.
     *
     * Each copy happens inside the critical section, so no caller observes a half-updated entry.
     */
    private val mutex = Mutex()

    override suspend fun get(key: String): ByteArray? {
        requireRepresentableKey(key)
        return mutex.withLock {
            insideCriticalSection()
            values[key]?.copyOf()
        }
    }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        requireRepresentableKey(key)
        mutex.withLock {
            insideCriticalSection()
            values[key] = value.copyOf()
        }
    }

    override suspend fun remove(key: String) {
        requireRepresentableKey(key)
        mutex.withLock {
            insideCriticalSection()
            values.remove(key)
        }
    }
}
