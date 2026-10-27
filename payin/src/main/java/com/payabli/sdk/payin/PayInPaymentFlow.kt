package com.payabli.sdk.payin

import android.os.SystemClock
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInStoreRequest
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInTransaction
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
 * The one implementation of [PayabliPayIn], and the only type in this module that knows both a
 * session and a form exist. `internal`, so what a host can call is [PayabliPayIn]'s members and
 * nothing else; it sits here because [PayabliPayIn] is sealed. **[scope] is the host's.**
 *
 * @property reports built once here, from the session that created this flow, and handed to the form.
 */
internal class PayInPaymentFlow private constructor(
    private val entryPoint: String,
    private val scope: CoroutineScope,
    private val submission: PayInSubmission,
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
    ) : this(
        session.transport,
        session.telemetry.environment,
        entryPoint,
        scope,
        IO_DISPATCHER,
        // Counts device sleep, which the held key's window spans: a payer who backgrounds the app after an
        // unknown outcome comes back to a key `System.nanoTime` still reads as young.
        SystemClock::elapsedRealtimeNanos,
        telemetry = session.telemetry,
    )

    private constructor(
        transport: PayabliTransport,
        environment: PayabliEnvironment,
        entryPoint: String,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        elapsedRealtimeNanos: () -> Long,
        logger: SdkLogger? = null,
        telemetry: TelemetrySessionContext? = null,
    ) : this(
        entryPoint,
        scope,
        PayInSubmission(
            moneyIn = if (logger == null) MoneyInClient(transport) else MoneyInClient(transport, logger),
            storage = if (logger == null) TokenStorageClient(transport) else TokenStorageClient(transport, logger),
            dispatcher = dispatcher,
            environment = environment,
            // Random per attempt, so two payments from one screen are never one request to the service.
            newIdempotencyKey = { UUID.randomUUID().toString() },
            elapsedRealtimeNanos = elapsedRealtimeNanos,
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
     * Consumes a terminal state, returning to [PayInSubmissionState.Idle]. Called by `PayabliPayInForm`
     * once an outcome has reached the functions the caller supplied: a retained outcome is delivered
     * again after the next configuration change, and a navigation then fires twice.
     *
     * @return true once the state is [PayInSubmissionState.Idle]; false while a submission is in flight.
     */
    @JvmSynthetic
    internal fun consume(): Boolean = submission.reset()

    /**
     * Whether the service may have acted on the request [outcome] answers, which is when a form lets the instrument go.
     *
     * @param outcome a state read from [state].
     * @return true when it may have; false otherwise.
     */
    @JvmSynthetic
    internal fun sent(outcome: PayInSubmissionState): Boolean = submission.sent(outcome)

    /**
     * Runs [operation] on [scope], for a caller with no coroutine at the call site. What the SDK's
     * own form calls: the outcome is readable through [state] alone, and the suspending members
     * below return it instead.
     *
     * @param operation what the submission does.
     * @param values what the payer entered, and the instrument they entered it for.
     * @return true when the submission started; false when one was already in flight, having sent nothing.
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
     * Takes the payment, returning what the service said. `internal` with the shape it will keep: the
     * caller is a host that draws its own form, and that integration mode is not exposed yet. `Result`
     * rather than a thrown exception, because a decline is an outcome rather than a defect.
     *
     * @param options what to charge and how the request should be treated.
     * @param values what the payer entered, and the instrument they entered it for.
     * @return the outcome, or the failure.
     */
    internal suspend fun capture(
        options: PayInTransactionOptions,
        values: PayInFormValues,
    ): Result<PayInResult> = payment(PayabliPayInOperation.Capture(options), values)

    /**
     * Places a hold without taking it, which the service does for entered card data only.
     *
     * @param options what to hold and how the request should be treated.
     * @param values what the payer entered, and the instrument they entered it for.
     * @return the outcome, or the failure.
     */
    internal suspend fun authorize(
        options: PayInTransactionOptions,
        values: PayInFormValues,
    ): Result<PayInResult> = payment(PayabliPayInOperation.Authorize(options), values)

    /**
     * Stores the instrument, so a later transaction charges it without the details again.
     *
     * @param values what the payer entered, and the instrument they entered it for.
     * @param options everything about storing a method other than the instrument.
     * @return the stored method, or the failure.
     */
    internal suspend fun storeMethod(
        values: PayInFormValues,
        options: PayInStoreOptions = PayInStoreOptions(),
    ): Result<PayInStoredMethod> =
        submission.submit(entryPoint, PayabliPayInOperation.StoreMethod(options), values).asStoredMethod()

    override suspend fun capture(request: PayInRequest): Result<PayInResult> =
        submission.capture(entryPoint, request).asPayment()

    override suspend fun storeMethod(request: PayInStoreRequest): Result<PayInStoredMethod> =
        submission.storeMethod(entryPoint, request).asStoredMethod()

    override suspend fun authorize(request: PayInRequest): Result<PayInResult> =
        submission.authorize(entryPoint, request).asPayment()

    override suspend fun captureAuthorizedTransaction(request: PayInAuthorizedRequest): Result<PayInResult> =
        submission.captureAuthorized(entryPoint, request).asPayment()

    override suspend fun voidTransaction(transId: String): Result<PayInResult> =
        submission.void(entryPoint, transId).asPayment()

    override suspend fun readTransaction(transId: String): Result<PayInTransaction> =
        submission.readTransaction(entryPoint, transId)

    private suspend fun payment(
        operation: PayabliPayInOperation,
        values: PayInFormValues,
    ): Result<PayInResult> = submission.submit(entryPoint, operation, values).asPayment()

    private fun PayInSubmissionState?.asPayment(): Result<PayInResult> =
        when (this) {
            is PayInSubmissionState.Succeeded.Payment -> Result.success(result)
            else -> Result.failure(asFailure())
        }

    private fun PayInSubmissionState?.asStoredMethod(): Result<PayInStoredMethod> =
        when (this) {
            is PayInSubmissionState.Succeeded.Method -> Result.success(storedMethod)
            else -> Result.failure(asFailure())
        }

    /**
     * The failure behind a state that is not the success the caller asked for: the cause already
     * says whether the request may have been carried out, so this reads it rather than deciding it
     * again. A null state is a submission refused because one was already in flight.
     *
     * @return the failure behind the state, or the refusal for a submission already in flight.
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
         * Builds one, and is the only way to: a factory rather than a constructor because `internal` is
         * a Kotlin boundary and not a JVM one, and a function can be `@JvmSynthetic` where a constructor
         * cannot, so construction goes through here and the constructors are private.
         *
         * @param session an initialized session.
         * @param entryPoint the partner integration point every request is sent to.
         * @param scope where a submission started by the form runs.
         * @return the flow, as a [PayabliPayIn].
         */
        @JvmSynthetic
        internal fun over(
            session: PayabliSession,
            entryPoint: String,
            scope: CoroutineScope,
        ): PayInPaymentFlow = PayInPaymentFlow(session, entryPoint, scope)

        /**
         * The seam a test builds over, which takes a transport rather than a session.
         * [elapsedRealtimeNanos] defaults to the production clock; a JVM test has to pass one, because
         * `SystemClock` does not exist there and the default throws rather than measuring anything.
         *
         * @param transport the transport every request goes through.
         * @param entryPoint the partner integration point every request is sent to.
         * @param scope where a submission started by the form runs.
         * @param dispatcher where the blocking work runs.
         * @param elapsedRealtimeNanos the clock a retry key's window is measured on.
         * @param logger where the submissions are logged, or null for none.
         * @param telemetry the session the reports are attributed to, or null for none.
         * @param environment the environment the held keys are kept under.
         * @return the flow, as a [PayabliPayIn].
         */
        @JvmSynthetic
        internal fun over(
            transport: PayabliTransport,
            entryPoint: String,
            scope: CoroutineScope,
            dispatcher: CoroutineDispatcher,
            elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
            logger: SdkLogger? = null,
            telemetry: TelemetrySessionContext? = null,
            environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
        ): PayInPaymentFlow =
            PayInPaymentFlow(
                transport,
                environment,
                entryPoint,
                scope,
                dispatcher,
                elapsedRealtimeNanos,
                logger,
                telemetry,
            )
    }
}
