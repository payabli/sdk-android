package com.payabli.sdk.payin.payment

import androidx.compose.runtime.Immutable
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod
import java.util.Collections

/**
 * Where a submission has got to.
 *
 * Carries the typed cause of a failure and the field the refusal blamed, so a host can branch on the cause —
 * retry, re-enter, re-initialize — and the form can mark the box the payer has to change.
 *
 * No field value and no buffer is in here. A card number reaches the request and nothing else.
 *
 * `@Immutable`, and [Failed] copies the map it is handed, so what Compose reads cannot change under it.
 */
@Immutable
public sealed class PayInSubmissionState {
    /** Nothing has been submitted. */
    public data object Idle : PayInSubmissionState()

    /** A request is in flight, and a second submission is refused while this stands. */
    public data object Submitting : PayInSubmissionState()

    /**
     * The service accepted it.
     *
     * Two shapes, because the operations produce two: a transaction, or a method stored for later use. A caller
     * asking only whether it worked reads this type and neither of them.
     */
    public sealed class Succeeded : PayInSubmissionState() {
        /** A capture, an authorization, or the capture of an authorization. */
        public class Payment(
            public val result: PayInResult,
        ) : Succeeded() {
            override fun toString(): String = "PayInSubmissionState.Succeeded.Payment(code=${result.code})"
        }

        /** A method the service stored, identified so a later transaction can charge it. */
        public class Method(
            public val storedMethod: PayInStoredMethod,
        ) : Succeeded() {
            override fun toString(): String = "PayInSubmissionState.Succeeded.Method"
        }
    }

    /**
     * It did not go through.
     *
     * **[cause] is where the payment's outcome is reported.** Where a money-moving attempt may have been
     * carried out, [cause] is a [PayInException.Unsettled]; anywhere else it is the exception a client
     * raised, directly. [PayabliException.type] is the raised one's either way, so a decline, a validation
     * failure and a network failure stay tellable apart without unwrapping. [fieldErrors] is what the refusal
     * blamed, per field, and is empty when it blamed none.
     *
     * **A [PayInException.Unsettled] does not hand back the exception it wrapped.** Its own cause names the
     * failing type and withholds the message, which can quote what was submitted, so it is safe to log and
     * cannot be unwrapped or type-checked back to the original. Branch on [PayabliException.type], and read
     * [PayInException.Unsettled.paymentTransId] for the payment to reconcile.
     *
     * **To try a payment whose outcome is unknown again, submit the same `paymentReference` with `attempt` set
     * to `PayabliAttempt.Retry`.** [PayInException.Unsettled.paymentReference] names it, and is the SDK's own
     * when none was set. The SDK sends the key held for that payment while the service would still recognise
     * it, so the repeat is not taken as a second payment; later than that, it goes out under a new key. The key
     * itself is not handed out.
     */
    public class Failed(
        public val cause: PayabliException,
        fieldErrors: Map<PayInField, PayInFieldError> = emptyMap(),
    ) : PayInSubmissionState() {
        public val fieldErrors: Map<PayInField, PayInFieldError> =
            Collections.unmodifiableMap(fieldErrors.toMap())

        /**
         * The cause renders as its own classification, which carries no server text.
         *
         * @return the cause's classification and the rejected field names.
         */
        override fun toString(): String = "PayInSubmissionState.Failed(cause=$cause, fields=${fieldErrors.keys})"
    }
}
