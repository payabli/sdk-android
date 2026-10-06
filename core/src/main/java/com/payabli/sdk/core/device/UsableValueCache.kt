package com.payabli.sdk.core.device

/**
 * Holds a value once a read returns a usable one. An unusable read is returned and not held, so the next call
 * reads again.
 */
internal class UsableValueCache<T : Any>(
    private val usable: (T) -> Boolean,
) {
    @Volatile
    private var held: T? = null

    fun get(read: () -> T): T =
        held ?: synchronized(this) {
            held ?: read().also { if (usable(it)) held = it }
        }
}
