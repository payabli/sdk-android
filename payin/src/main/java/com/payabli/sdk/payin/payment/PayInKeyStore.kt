package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE

/**
 * The keys of attempts whose outcome nobody knows, one per payment per entry point and environment, shared by every
 * instance in this process, so a rebuilt facade resends what the last one held. Each ages out on
 * [IDEMPOTENCY_KEY_MAX_AGE]. Nothing here suspends: a submission settles its key on the canceled path too.
 */
internal class PayInKeyStore(
    private val elapsedRealtimeNanos: () -> Long,
    private val logger: SdkLogger,
) {
    /**
     * The key held for [payment], or null when none is worth sending: past the window the service runs a key as
     * a new payment.
     *
     * @param entry the entry point the payment is sent to.
     * @param environment the environment the payment is sent to.
     * @param payment what names the payment.
     * @param now the clock, read as the key is chosen.
     * @return the key held for [payment], or null once it is too old to send.
     */
    fun held(
        entry: String,
        environment: PayabliEnvironment,
        payment: String,
        now: Long,
    ): HeldKey? =
        synchronized(SHARED_LOCK) {
            sweep(now)
            SHARED_HELD.firstOrNull { it.matches(entry, environment) && it.payment == payment }
        }

    /**
     * Keeps [key] for [payment] without displacing what protects another payment: the same key merges, keeping
     * the latest reservation, and a payment gets no second key.
     *
     * @param entry the entry point the payment is sent to.
     * @param environment the environment the payment is sent to.
     * @param payment what names the payment.
     * @param key the key to keep.
     * @param reservedAt when the key's window starts.
     * @param description the payment the key was sent for, or null on a route that names a transaction.
     */
    fun hold(
        entry: String,
        environment: PayabliEnvironment,
        payment: String,
        key: String,
        reservedAt: Long,
        description: PayInPaymentDescription?,
    ) {
        synchronized(SHARED_LOCK) {
            sweep(elapsedRealtimeNanos())
            val partition = SHARED_HELD.filter { it.matches(entry, environment) }
            val same =
                SHARED_HELD.indexOfFirst {
                    it.matches(entry, environment) &&
                        it.payment == payment &&
                        it.key == key
                }
            when {
                same >= 0 -> {
                    val existing = SHARED_HELD[same]
                    // Replaced, never changed in place: a caller reads the key after the lock is released.
                    // The first description stays: it is the payment the key was minted for.
                    SHARED_HELD[same] =
                        HeldKey(
                            entry,
                            environment.name,
                            payment,
                            key,
                            maxOf(existing.reservedAt, reservedAt),
                            existing.description ?: description,
                        )
                }

                partition.any { it.payment == payment } -> Unit
                partition.size >= HELD_KEYS_MAX -> {
                    if (SHARED_REPORTED_FULL.add(partitionOf(entry, environment))) {
                        logger.warn(LogField.safe("event", "payin_retry_keys_full")) {
                            "a payment's key was not held, because too many are unresolved at once"
                        }
                    }
                }

                else -> {
                    SHARED_REPORTED_FULL.remove(partitionOf(entry, environment))
                    SHARED_HELD.add(0, HeldKey(entry, environment.name, payment, key, reservedAt, description))
                }
            }
        }
    }

    /**
     * Forgets [payment]'s key [key], and no other.
     *
     * @param entry the entry point the payment is sent to.
     * @param environment the environment the payment is sent to.
     * @param payment what names the payment.
     * @param key the key an answer arrived under.
     */
    fun forget(
        entry: String,
        environment: PayabliEnvironment,
        payment: String,
        key: String,
    ) {
        synchronized(SHARED_LOCK) {
            SHARED_HELD.removeAll { it.matches(entry, environment) && it.payment == payment && it.key == key }
        }
    }

    /**
     * Drops every key too old to send, in every entry point: a flow meets many transactions and revisits few, and
     * an entry left in place takes room a later payment needs. Called with [SHARED_LOCK] held.
     *
     * @param now the clock.
     */
    private fun sweep(now: Long) {
        SHARED_HELD.removeAll { now - it.reservedAt >= IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds }
    }

    private fun partitionOf(
        entry: String,
        environment: PayabliEnvironment,
    ): String = "${environment.name}/$entry"

    internal companion object {
        /** Payments holding a key at once, per entry point and environment. Past it a key is minted and not held. */
        const val HELD_KEYS_MAX: Int = 16

        private val SHARED_LOCK = Any()
        private val SHARED_HELD: MutableList<HeldKey> = ArrayList()

        /** The entry points that have already said they are full, so each says so once until it has room again. */
        private val SHARED_REPORTED_FULL: MutableSet<String> = HashSet()

        /** Drops every held key under the same lock the submissions take. Process state for tests. */
        @JvmSynthetic
        fun forgetHeld() {
            synchronized(SHARED_LOCK) {
                SHARED_HELD.clear()
                SHARED_REPORTED_FULL.clear()
            }
        }
    }
}

/**
 * A key held for a payment at one entry point under one environment, and when its window started. Not a
 * `data class`: the synthesized `toString` would put the key into any assertion failure or crash report that
 * renders one.
 *
 * @property entry the entry point the payment is sent to.
 * @property environment the name of the environment the payment is sent to.
 * @property payment what names the payment: its route, and its transaction or payment reference.
 * @property key the idempotency key a repeat of the payment carries.
 * @property reservedAt when the key's window started, on a monotonic clock that counts device sleep.
 * @property description the payment the key was first sent for, or null on a route that names a transaction.
 */
internal class HeldKey(
    val entry: String,
    val environment: String,
    val payment: String,
    val key: String,
    val reservedAt: Long,
    val description: PayInPaymentDescription?,
) {
    fun matches(
        entry: String,
        environment: PayabliEnvironment,
    ): Boolean = this.entry == entry && this.environment == environment.name
}
