package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import com.payabli.sdk.payin.PayabliPayIn
import kotlinx.coroutines.CompletableDeferred

/**
 * A handle with no SDK behind it, for a screen's own tests.
 *
 * `PayabliPayIn` is sealed, so a JVM test cannot build one and cannot fake one either. Without this every
 * view model that reverses a payment would be reachable only from a device, and the branch that reports the
 * outcome would go untested at the tier that runs on every pull request.
 *
 * [formTarget] is null, which is what stops this being handed to a form: nothing draws one over a double.
 */
internal class FakePayInFlowHandle(
    override val isBusy: Boolean = false,
    /** Held open to keep a reversal in flight, so a test can look at the screen while one is running. */
    private val released: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    private val answer: (String, String) -> PayInOutcome,
) : PayInFlowHandle {
    /** Every reversal asked for, so a test can assert what was sent and under which key. */
    val reversals: MutableList<Pair<String, String>> = mutableListOf()

    override val formTarget: PayabliPayIn? get() = null

    @Composable
    override fun isSubmitting(): Boolean = false

    override suspend fun voidTransaction(
        transId: String,
        idempotencyKey: String,
    ): PayInOutcome {
        reversals += transId to idempotencyKey
        released.await()
        return answer(transId, idempotencyKey)
    }
}
