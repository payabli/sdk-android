package com.payabli.sdk.testutils.storage

import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.requireRepresentableKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [PayabliSecureStorage] that keeps values unencrypted in a map, for tests only. It copies arrays in and out,
 * so a consumer wiping what it passed to [set] cannot blank the stored value, and it enforces the shipping
 * store's key rule through [requireRepresentableKey], so a test cannot pass on a key production rejects.
 */
public class InMemorySecureStorage(
    /**
     * Runs inside the critical section, so a test can hold the lock and prove a second caller cannot enter.
     *
     * A no-op by default, which leaves every other use unchanged. Asserting exclusion directly is deterministic
     * where asserting its consequence is not: a lost-update test catches a removed mutex about one run in five,
     * and raising the writer count does not help, since the critical section is a single map assignment.
     *
     * A hook here costs nothing that the earlier debate about a seam in `KeystoreValueCipher` cost. This module is
     * never published and never linked by an app, so this is test-only control flow in test-only code rather than
     * in a class that owns key material.
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
