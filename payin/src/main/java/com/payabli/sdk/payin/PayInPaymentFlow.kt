package com.payabli.sdk.payin

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayInSubmission
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.telemetry.PayInFormReports
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The one implementation of [PayabliPayIn], and the only type in this module that knows both a session and a
 * form exist: the form knows this and nothing under it, and the layers under it know nothing about a screen.
 *
 * `internal`, so what a host can call is [PayabliPayIn]'s members and nothing else. It sits in this
 * package rather than beside the submission machinery because [PayabliPayIn] is sealed, and Kotlin
 * requires a sealed type's implementations to share its package. The four members below
 * that the form reaches are the reason this type exists separately: they are how a composition drives a
 * submission, and none of them is an operation a host performs.
 *
 * **[scope] is the host's**, and cancelling it cancels a submission in flight. Canceling does not un-charge a
 * card, so whether a payment dies with a screen is the host's decision rather than this SDK's.
 *
 * The constructor parameters are documented on [PayabliPayIn.invoke], which is how a host reaches this.
 */
internal class PayInPaymentFlow private constructor(
    private val entryPoint: String,
    private val scope: CoroutineScope,
    private val submission: PayInSubmission,
    /** Built once here, from the session that created this flow, and handed to the form. */
    @get:JvmSynthetic
    internal val reports: PayInFormReports,
) : PayabliPayIn() {
    /**
     * What the payer has entered, which lives here rather than in the form's composition.
     *
     * A rotation, a switch to another tab and a return from a pushed screen all end that composition. Held
     * there, a card number entered before a rotation is a card number entered again afterwards.
     */
    @get:JvmSynthetic
    internal val draft: PayInFormDraft = PayInFormDraft()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { draft.clear() }
    }

    private constructor(
        session: PayabliSession,
        entryPoint: String,
        scope: CoroutineScope,
    ) : this(session.transport, entryPoint, scope, IO_DISPATCHER, telemetry = session.telemetry)

    private constructor(
        transport: PayabliTransport,
        entryPoint: String,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        logger: SdkLogger? = null,
        telemetry: TelemetrySessionContext? = null,
    ) : this(
        entryPoint,
        scope,
        PayInSubmission(
            moneyIn = if (logger == null) MoneyInClient(transport) else MoneyInClient(transport, logger),
            storage = if (logger == null) TokenStorageClient(transport) else TokenStorageClient(transport, logger),
            dispatcher = dispatcher,
            // Random per attempt, so two payments from one screen are never one request to the service.
            newIdempotencyKey = { UUID.randomUUID().toString() },
            session = telemetry,
        ),
        PayInFormReports(telemetry?.forEntryPoint(entryPoint)),
    )

    /**
     * Where the current submission has got to: what a form renders, and what a host reads for its own chrome.
     *
     * A terminal state stands until the form has delivered it, which is what lets a rotation mid-flight still
     * deliver an outcome. The form consumes it immediately afterwards, so nothing here has to be cleared by
     * whoever reads it.
     */
    override val state: StateFlow<PayInSubmissionState> get() = submission.state

    /**
     * Consumes a terminal state, returning to [PayInSubmissionState.Idle].
     *
     * Called by `PayabliPayInForm` once an outcome has reached the functions the caller supplied. A retained
     * outcome is delivered again after the next configuration change, and a navigation then fires twice for one
     * payment.
     *
     * Returns false while a submission is in flight, so nothing can clear the state out from under one.
     */
    @JvmSynthetic
    internal fun consume(): Boolean = submission.reset()

    /**
     * Runs [operation] on [scope], for a caller with no coroutine at the call site.
     *
     * What the SDK's own form calls. The outcome is readable through [state] alone, which is what a
     * fire-and-forget start can offer; the suspending members below return it instead.
     *
     * Returns false when a submission is already in flight, having sent nothing.
     */
    @JvmSynthetic
    internal fun start(
        operation: PayabliPayInOperation,
        values: PayInFormValues,
    ): Boolean {
        // What the single flight answered, not what the state said. The state is published while the guard is
        // still held, so a caller reading it can be told a submission was accepted that the guard then refused,
        // and the form would wait for an outcome nothing will publish.
        //
        // Undispatched, so the guard is taken and `Submitting` is published before this returns. Dispatched, two
        // forms on one flow both read a non-submitting state and both are told they were accepted.
        var reserved = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            submission.submit(entryPoint, operation, values) { reserved = it }
        }
        return reserved
    }

    /**
     * Takes the payment, returning what the service said.
     *
     * `internal` with the shape it will keep. The caller for these three is a host that draws its own form,
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

    override suspend fun captureAuthorizedTransaction(request: PayInAuthorizedRequest): Result<PayInResult> =
        submission.captureAuthorized(entryPoint, request).asPayment()

    override suspend fun voidTransaction(
        transId: String,
        idempotencyKey: String?,
    ): Result<PayInResult> = submission.void(entryPoint, transId, idempotencyKey).asPayment()

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

    internal companion object {
        /**
         * The one dispatcher pick in this module, at the layer a host calls, as `PayabliSession` is for `:core`.
         *
         * Everything below takes it as a required parameter, so no layer can quietly run somewhere else. The
         * day the session accepts a host-supplied dispatcher this becomes a constructor parameter and the
         * narrowing reaches the whole module; today the session hardcodes its own and there is nothing to
         * inherit.
         */
        private val IO_DISPATCHER: CoroutineDispatcher = Dispatchers.IO

        /**
         * Builds one, and is the only way to.
         *
         * A factory rather than a constructor because `internal` is a Kotlin boundary and not a JVM one: an
         * internal constructor compiles to a public one, so Java could build this type directly and reach the
         * form-only members below it. A constructor cannot be marked `@JvmSynthetic` and a function can, so
         * construction goes through here and the constructors are private.
         */
        @JvmSynthetic
        internal fun over(
            session: PayabliSession,
            entryPoint: String,
            scope: CoroutineScope,
        ): PayInPaymentFlow = PayInPaymentFlow(session, entryPoint, scope)

        /** The seam a test builds over, which takes a transport rather than a session. */
        @JvmSynthetic
        internal fun over(
            transport: PayabliTransport,
            entryPoint: String,
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            logger: SdkLogger? = null,
            telemetry: TelemetrySessionContext? = null,
        ): PayInPaymentFlow = PayInPaymentFlow(transport, entryPoint, scope, dispatcher, logger, telemetry)
    }
}
