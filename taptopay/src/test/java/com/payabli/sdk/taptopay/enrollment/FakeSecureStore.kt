package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException

/**
 * An in-memory [PayabliSecureStorage] that records what it was asked and can be told to fail.
 *
 * `:core` ships one of these and it cannot be borrowed: `InMemorySecureStorage` is `internal` to `:core` and
 * lives in a `sharedTest` directory wired into that module's compilations only. The interface is reachable,
 * so the cost of doing without is this file — the same trade `FakeDeviceTransport` records.
 *
 * **It enforces the key rule the shipping store enforces.** A fixture that accepts a name the real store
 * would reject is a fixture that lets a defect through, so the UTF-8 round trip is checked here too.
 */
internal class FakeSecureStore(
    /** Returns the failure to raise for an operation, or null to let it through. */
    private val failWith: (operation: String, key: String) -> SecureStorageException? = { _, _ -> null },
    /**
     * Awaited once, on the first read, so a test can hold one caller inside the coordinator.
     *
     * A rendezvous, not a delay: the second caller genuinely lands while the first is in flight, instead of
     * the test hoping it does.
     */
    private val firstReadGate: (suspend () -> Unit)? = null,
    /** Appended to on every call, so a test can assert order across this and the transport at once. */
    private val trace: MutableList<String> = mutableListOf(),
) : PayabliSecureStorage {
    private val entries = LinkedHashMap<String, ByteArray>()

    val operations: List<String> get() =
        trace.filter {
            it.startsWith("get:") ||
                it.startsWith("set:") ||
                it.startsWith("remove:")
        }

    private var firstReadSeen = false

    override suspend fun get(key: String): ByteArray? {
        require(
            key.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == key,
        ) { "key must survive a UTF-8 round trip" }
        if (!firstReadSeen) {
            firstReadSeen = true
            firstReadGate?.invoke()
        }
        trace += "get:$key"
        failWith("get", key)?.let { throw it }
        return entries[key]?.copyOf()
    }

    override suspend fun set(
        key: String,
        value: ByteArray,
    ) {
        require(
            key.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == key,
        ) { "key must survive a UTF-8 round trip" }
        trace += "set:$key"
        failWith("set", key)?.let { throw it }
        entries[key] = value.copyOf()
    }

    override suspend fun remove(key: String) {
        trace += "remove:$key"
        failWith("remove", key)?.let { throw it }
        entries.remove(key)
    }

    /** What is stored, read without recording an operation. */
    fun peek(key: String): ByteArray? = entries[key]?.copyOf()

    companion object {
        /** Fails [operation] with [failure] and lets everything else through. */
        fun failing(
            operation: String,
            failure: SecureStorageException,
        ): (String, String) -> SecureStorageException? = { op, _ -> failure.takeIf { op == operation } }
    }

    /** Seeds a value the way an earlier run would have left it. */
    fun seed(
        key: String,
        value: ByteArray,
    ) {
        entries[key] = value.copyOf()
    }

    val isEmpty: Boolean get() = entries.isEmpty()
}
