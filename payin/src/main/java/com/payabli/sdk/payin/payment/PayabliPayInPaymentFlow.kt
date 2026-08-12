package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInTransactionOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A payment form's submissions, for one entry point.
 *
 * The type a host holds, and the only one in this module that knows both a session and a form exist: the form
 * knows this and nothing under it, and the layers under it know nothing about a screen.
 *
 * **Hold one per screen, in whatever survives that screen's configuration changes** — a `ViewModel`, a
 * Decompose component, a presenter. [state] replays its latest value, so a collector arriving after a
 * rotation sees `Submitting` or the outcome rather than nothing.
 *
 * **[scope] is the host's**, and cancelling it cancels a submission in flight. Canceling does not un-charge a
 * card, so whether a payment dies with a screen is the host's decision rather than this SDK's.
 *
 * @param session an initialized session, whose transport carries the bearer, the one 401 recovery and the
 *   replay rule. This type holds no credential and no token path of its own.
 * @param entryPoint the partner integration point every request here is sent to.
 * @param scope where a submission started by the form runs. `viewModelScope` is the ordinary answer: it
 *   outlives a configuration change, so an outcome still arrives after a rotation, and it is cancelled when
 *   the screen goes for good. A scope tied to the composition — `rememberCoroutineScope` — cancels on
 *   rotation and loses the outcome of a request that has already reached the service.
 */
public class PayabliPayInPaymentFlow private constructor(
    private val entryPoint: String,
    private val scope: CoroutineScope,
    private val submission: PayInSubmission,
) {
    public constructor(
        session: PayabliSession,
        entryPoint: String,
        scope: CoroutineScope,
    ) : this(session.transport, entryPoint, scope, IO_DISPATCHER)

    /**
     * Against a transport directly, so this type is reachable from a test.
     *
     * `PayabliSession` cannot be built from outside `:core` — its test entry points are internal to that
     * module — so a session-only constructor would make everything here untestable.
     */
    internal constructor(
        transport: PayabliTransport,
        entryPoint: String,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        logger: SdkLogger? = null,
    ) : this(
        entryPoint,
        scope,
        PayInSubmission(
            moneyIn = if (logger == null) MoneyInClient(transport) else MoneyInClient(transport, logger),
            storage = if (logger == null) TokenStorageClient(transport) else TokenStorageClient(transport, logger),
            dispatcher = dispatcher,
        ),
    )

    /**
     * Where the current submission has got to: what a form renders, and what a host reads for its own chrome.
     *
     * A terminal state stands until [acknowledge] consumes it, which is what lets a rotation mid-flight still
     * deliver an outcome.
     */
    public val state: StateFlow<PayInSubmissionState> get() = submission.state

    /**
     * Consumes a terminal state, returning to [PayInSubmissionState.Idle].
     *
     * A host that navigates on a success calls this once it has, or the retained state re-delivers the same
     * success after the next configuration change and navigation fires twice. Returns false while a
     * submission is in flight, so a caller cannot clear the state out from under one.
     */
    public fun acknowledge(): Boolean = submission.reset()

    /**
     * Runs [operation] on [scope], for a caller with no coroutine at the call site.
     *
     * What the SDK's own form calls. The outcome is readable through [state] alone, which is what a
     * fire-and-forget start can offer; the suspending members below return it instead.
     *
     * Returns false when a submission is already in flight, having sent nothing.
     */
    internal fun start(
        operation: PayabliPayInOperation,
        values: PayInFormValues,
    ): Boolean {
        if (state.value is PayInSubmissionState.Submitting) return false
        scope.launch { submission.submit(entryPoint, operation, values) }
        return true
    }

    /**
     * Takes the payment, returning what the service said.
     *
     * `internal` with the shape it will keep. The caller for these four is a host that draws its own form,
     * and that integration mode is not exposed yet. `Result` rather than a thrown exception, because a
     * decline is an outcome a caller acts on rather than a defect, and suspend-returning-`Result` is the
     * shape the SDK blueprint fixes for a one-shot call.
     */
    internal suspend fun capture(
        options: PayInTransactionOptions,
        values: PayInFormValues,
    ): Result<PayInResult> = payment(PayabliPayInOperation.Capture(options), values)

    /** Places a hold without taking it, which the service does for entered card data only. */
    internal suspend fun authorize(
        options: PayInTransactionOptions,
        values: PayInFormValues,
    ): Result<PayInResult> = payment(PayabliPayInOperation.Authorize(options), values)

    /** Stores the instrument, so a later transaction charges it without the details again. */
    internal suspend fun storeMethod(
        values: PayInFormValues,
        options: PayInStoreOptions = PayInStoreOptions(),
    ): Result<PayInStoredMethod> =
        when (val outcome = submission.submit(entryPoint, PayabliPayInOperation.StoreMethod(options), values)) {
            is PayInSubmissionState.Succeeded.Method -> Result.success(outcome.storedMethod)
            else -> Result.failure(outcome.asFailure())
        }

    /** Captures a transaction authorized earlier, in full or in part. Reads no form. */
    internal suspend fun captureAuthorized(request: PayInAuthorizedRequest): Result<PayInResult> =
        submission.captureAuthorized(request).asPayment()

    private suspend fun payment(
        operation: PayabliPayInOperation,
        values: PayInFormValues,
    ): Result<PayInResult> = submission.submit(entryPoint, operation, values).asPayment()

    private fun PayInSubmissionState?.asPayment(): Result<PayInResult> =
        when (this) {
            is PayInSubmissionState.Succeeded.Payment -> Result.success(result)
            else -> Result.failure(asFailure())
        }

    /**
     * The failure behind a state that is not the success the caller asked for.
     *
     * A null state is a submission refused because one was already in flight. Idle and Submitting cannot
     * arise for a call that has returned, and reporting them as a defect is what keeps this exhaustive
     * without inventing a plausible-looking failure for a state that cannot occur.
     */
    private fun PayInSubmissionState?.asFailure(): Throwable =
        when (this) {
            is PayInSubmissionState.Failed -> cause
            null -> PayInException.AlreadySubmitting()
            else -> IllegalStateException("a submission returned while its state read $this")
        }

    private companion object {
        /**
         * The one dispatcher pick in this module, at the layer a host calls, as `PayabliSession` is for `:core`.
         *
         * Everything below takes it as a required parameter, so no layer can quietly run somewhere else. The
         * day the session accepts a host-supplied dispatcher this becomes a constructor parameter and the
         * narrowing reaches the whole module; today the session hardcodes its own and there is nothing to
         * inherit.
         */
        val IO_DISPATCHER: CoroutineDispatcher = Dispatchers.IO
    }
}
