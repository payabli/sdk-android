package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE

/**
 * The key of every payment sent and not yet answered, per entry point and environment, shared by every instance in
 * this process. A key is recorded as it is minted and ages out on [IDEMPOTENCY_KEY_MAX_AGE] once no send of it is
 * under way. Nothing here suspends: a submission ends its send on the canceled path too.
 */
internal class PayInKeyStore(
    private val logger: SdkLogger,
) {
    /**
     * The key [payment]'s send carries: the one recorded for it, otherwise a new one, recorded as it is minted.
     * Reading and recording are one operation under one lock, so two facades sending one payment at once cannot
     * each find nothing and mint.
     *
     * A new payment whose reference has a recorded key is refused before anything is sent, and a declared retry
     * describing a different payment from the recorded one is refused too. The recorded key stays.
     *
     * @param entry the entry point the payment is sent to.
     * @param environment the environment the payment is sent to.
     * @param payment what names the payment.
     * @param now the clock, read as the key is chosen.
     * @param claimsHeld whether this send may carry a recorded key: a declared retry, or a route naming a transaction.
     * @param description the payment this send describes, or null on a route that names a transaction.
     * @param mint makes a new key.
     * @return the key to send, and whether it was already recorded.
     */
    fun reserve(
        entry: String,
        environment: PayabliEnvironment,
        payment: String,
        now: Long,
        claimsHeld: Boolean,
        description: PayInPaymentDescription?,
        mint: () -> String,
    ): Reserved =
        synchronized(SHARED_LOCK) {
            sweep(now)
            val held = SHARED_HELD.firstOrNull { it.matches(entry, environment) && it.payment == payment }
            if (held != null) {
                if (!claimsHeld) {
                    throw PayabliGenericException(
                        PayabliErrorType.PAYMENT_REFERENCE_OPEN,
                        PayabliErrorType.PAYMENT_REFERENCE_OPEN.message,
                    )
                }
                if (description != null && held.description?.matches(description) == false) {
                    throw PayabliGenericException(
                        PayabliErrorType.RETRY_NOT_SAME_PAYMENT,
                        PayabliErrorType.RETRY_NOT_SAME_PAYMENT.message,
                    )
                }
                held.sending++
                return@synchronized Reserved(held.key, reused = true, held.reservedAt, held.description ?: description)
            }
            val minted = mint()
            val partition = partitionOf(entry, environment)
            if (SHARED_HELD.count { it.matches(entry, environment) } >= HELD_KEYS_MAX) {
                if (SHARED_REPORTED_FULL.add(partition)) {
                    logger.warn(LogField.safe("event", "payin_retry_keys_full")) {
                        "a payment's key was not held, because too many are unresolved at once"
                    }
                }
            } else {
                SHARED_REPORTED_FULL.remove(partition)
                SHARED_HELD.add(
                    0,
                    HeldKey(entry, environment.name, payment, minted, now, description).apply { sending = 1 },
                )
            }
            Reserved(minted, reused = false, now, description)
        }

    /**
     * Ends a send of [payment]'s key [key]: an answer forgets it, and any other ending keeps it only while a send of
     * it may have reached the service or is still under way. A key not recorded, past the capacity or already
     * answered, is left alone.
     *
     * @param entry the entry point the payment is sent to.
     * @param environment the environment the payment is sent to.
     * @param payment what names the payment.
     * @param key the key the send carried.
     * @param ending how the send ended.
     */
    fun end(
        entry: String,
        environment: PayabliEnvironment,
        payment: String,
        key: String,
        ending: SendEnding,
    ) {
        synchronized(SHARED_LOCK) {
            val at =
                SHARED_HELD.indexOfFirst {
                    it.matches(entry, environment) && it.payment == payment && it.key == key
                }
            if (at < 0) return
            val held = SHARED_HELD[at]
            held.sending = maxOf(0, held.sending - 1)
            when (ending) {
                SendEnding.Answered -> SHARED_HELD.removeAt(at)
                is SendEnding.MayHaveArrived -> {
                    held.carried = true
                    held.reservedAt = maxOf(held.reservedAt, ending.at)
                }

                // The service has seen the key, so it was carried out; the clock it already had stands.
                SendEnding.RefusedAsRepeat -> held.carried = true
                SendEnding.Ended -> if (held.sending == 0 && !held.carried) SHARED_HELD.removeAt(at)
            }
        }
    }

    /**
     * Drops every key too old to send, in every entry point, unless a send of it is under way: a flow meets many
     * transactions and revisits few, and an entry left in place takes room a later payment needs. Called with
     * [SHARED_LOCK] held.
     *
     * @param now the clock.
     */
    private fun sweep(now: Long) {
        SHARED_HELD.removeAll {
            it.sending == 0 && now - it.reservedAt >= IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds
        }
    }

    private fun partitionOf(
        entry: String,
        environment: PayabliEnvironment,
    ): String = "${environment.name}/$entry"

    /**
     * A key [reserve] chose, whether it was already recorded, when its window started, and the payment it was first
     * sent for. Not a `data class`, and the key's getter is `@get:JvmSynthetic`, so neither a `toString` nor Java
     * reaches the key.
     */
    internal class Reserved(
        @get:JvmSynthetic val key: String,
        val reused: Boolean,
        val reservedAt: Long,
        val description: PayInPaymentDescription?,
    )

    internal companion object {
        /** Payments holding a key at once, per entry point and environment. Past it a key is minted and not held. */
        const val HELD_KEYS_MAX: Int = 16

        private val SHARED_LOCK = Any()
        private val SHARED_HELD: MutableList<HeldKey> = ArrayList()

        /** The entry points, per environment, that have said they are full, so each says so once until it has room. */
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

/** How a send of a recorded key ended, which decides whether the key is kept. */
internal sealed interface SendEnding {
    /** The service answered the payment, so a repeat of it would claim a payment it is not. */
    data object Answered : SendEnding

    /** The outcome is unknown and the send may have reached the service at [at], which restarts the key's window. */
    class MayHaveArrived(
        val at: Long,
    ) : SendEnding

    /** The service refused a resend as a repeat of a key it has seen. */
    data object RefusedAsRepeat : SendEnding

    /** Any other ending, including a send that never left: nothing about it shows the key reached the service. */
    data object Ended : SendEnding
}

/**
 * A key recorded for a payment at one entry point under one environment, and when its window started. Changed only
 * under the store's lock, and never handed out of it. Not a `data class`: the synthesized `toString` would put the
 * key into any assertion failure or crash report.
 *
 * @property entry the entry point the payment is sent to.
 * @property environment the name of the environment the payment is sent to.
 * @property payment what names the payment: its route, and its transaction or payment reference.
 * @property key the idempotency key a repeat of the payment carries.
 * @property description the payment the key was first sent for, or null on a route that names a transaction.
 */
internal class HeldKey(
    val entry: String,
    val environment: String,
    val payment: String,
    val key: String,
    reservedAt: Long,
    val description: PayInPaymentDescription?,
) {
    /** When the key's window started, on a monotonic clock that counts device sleep. */
    var reservedAt: Long = reservedAt

    /** Sends of this key not yet ended, which may still reach the service, so no sweep drops it meanwhile. */
    var sending: Int = 0

    /** Whether the service may know this key, which keeps it held when no send of it is under way. */
    var carried: Boolean = false

    fun matches(
        entry: String,
        environment: PayabliEnvironment,
    ): Boolean = this.entry == entry && this.environment == environment.name
}
