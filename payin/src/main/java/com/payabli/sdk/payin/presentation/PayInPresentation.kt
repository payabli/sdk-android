package com.payabli.sdk.payin.presentation

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Launches one activity-result contract from the SDK's own [PayInPresentationActivity] and suspends until it answers.
 * Each presentation waits in a table shared by the process, keyed by the id its Intent carries.
 */
internal object PayInPresentation {
    internal const val EXTRA_ID = "com.payabli.sdk.payin.presentation.ID"

    // A plain map under a monitor, not ConcurrentHashMap.computeIfAbsent, which is API 24 against this module's 23.
    private val lock = Any()
    private val pending: MutableMap<Long, Pending<*, *, *>> = HashMap()
    private var lastId = 0L

    /**
     * Opens [contract] with [input] over [activity] and returns how it ended. Cancelling the call finishes the
     * presentation Activity. A process that dies meanwhile takes the call with it, and the restored Activity closes.
     *
     * @param activity the Activity the presentation starts from.
     * @param contract the contract to launch, such as Google Pay's `GetPaymentDataResult`.
     * @param input what [contract] is launched with.
     * @param outcome reads the contract's output as a completion or a cancel.
     * @return the contract's answer, or [PayInPresentationOutcome.Cancelled] if the Activity finished without one.
     */
    suspend fun <I, O, R> present(
        activity: Activity,
        contract: ActivityResultContract<I, O>,
        input: I,
        outcome: (O) -> PayInPresentationOutcome<R>,
    ): PayInPresentationOutcome<R> =
        suspendCancellableCoroutine { continuation ->
            val entry = Pending(contract, input, outcome, continuation)
            val id =
                synchronized(lock) {
                    lastId += 1
                    pending[lastId] = entry
                    lastId
                }
            continuation.invokeOnCancellation { withdraw(id)?.close() }
            try {
                activity.startActivity(Intent(activity, PayInPresentationActivity::class.java).putExtra(EXTRA_ID, id))
            } catch (e: RuntimeException) {
                withdraw(id)
                throw e
            }
        }

    /**
     * Binds [activity] to the presentation [id] names, so a cancelled call can finish it.
     *
     * @param id the id from the Activity's Intent.
     * @param activity the instance now showing it, which replaces any instance before a recreation.
     * @return the presentation, or null when nothing waits on [id].
     */
    fun attach(
        id: Long,
        activity: ComponentActivity,
    ): Pending<*, *, *>? = synchronized(lock) { pending[id]?.also { it.activity = activity } }

    /**
     * Unbinds [activity] from [id] if it is still the bound instance, so a destroyed instance isn't held.
     *
     * @param id the presentation's id.
     * @param activity the instance being destroyed.
     */
    fun detach(
        id: Long,
        activity: ComponentActivity,
    ) {
        synchronized(lock) { pending[id]?.takeIf { it.activity === activity }?.activity = null }
    }

    /**
     * Hands [outcome] to the call waiting on [id], once. A second delivery, or one after the call was cancelled, is
     * dropped.
     *
     * @param id the presentation's id.
     * @param outcome how it ended.
     */
    fun deliver(
        id: Long,
        outcome: PayInPresentationOutcome<*>,
    ) {
        withdraw(id)?.resume(outcome)
    }

    /** Presentations still waiting. Process state for tests. */
    @JvmSynthetic
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun withdraw(id: Long): Pending<*, *, *>? = synchronized(lock) { pending.remove(id) }

    /** One presentation: what to launch, how to read its answer, and the call waiting on it. */
    internal class Pending<I, O, R>(
        private val contract: ActivityResultContract<I, O>,
        private val input: I,
        private val outcome: (O) -> PayInPresentationOutcome<R>,
        private val continuation: CancellableContinuation<PayInPresentationOutcome<R>>,
    ) {
        /** The instance showing it, guarded by the table's lock. */
        var activity: ComponentActivity? = null

        /**
         * Registers the contract on [activity], which has to happen in `onCreate`, and launches it if [launch].
         *
         * @param activity the presentation Activity, in `onCreate`.
         * @param launch false when a recreated instance is waiting on a contract already launched.
         * @param onOutcome receives the contract's answer, read through this presentation's reader.
         */
        fun start(
            activity: ComponentActivity,
            launch: Boolean,
            onOutcome: (PayInPresentationOutcome<R>) -> Unit,
        ) {
            val launcher = activity.registerForActivityResult(contract) { onOutcome(outcome(it)) }
            if (launch) launcher.launch(input)
        }

        @Suppress("UNCHECKED_CAST")
        fun resume(value: PayInPresentationOutcome<*>) {
            // Only this presentation's Activity delivers to it, and only what its own reader produced.
            continuation.resume(value as PayInPresentationOutcome<R>)
        }

        fun close() {
            val shown = synchronized(lock) { activity }
            shown?.runOnUiThread { shown.finish() }
        }
    }
}
