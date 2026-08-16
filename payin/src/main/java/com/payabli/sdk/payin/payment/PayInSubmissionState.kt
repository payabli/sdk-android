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
     * [cause] is the exception either client raised, so a decline, a validation failure and a network failure
     * stay tellable apart. [fieldErrors] is what the refusal blamed, per field, and is empty when it blamed
     * none.
     */
    public class Failed(
        public val cause: PayabliException,
        fieldErrors: Map<PayInField, PayInFieldError> = emptyMap(),
        /**
         * The key to resend, when this failure leaves the outcome unknown.
         *
         * Present on the operations that move money — a capture, an authorization, the capture of an earlier
         * authorization — for a cancellation, a network failure, a 5xx, a response that could not be decoded,
         * and an unexpected error. In each of those the payment may already have been taken, and a retry
         * carrying this key is recognized as the repeat it is instead of acting twice.
         *
         * Null when the outcome is known, as a decline, a local refusal or a rejected credential is, where a
         * retry is a new attempt.
         *
         * **Also null for storing a payment method, whatever the failure**, because a repeat is not
         * recognizable there and no key sent with a store is read by anything. A store whose outcome is
         * unknown is settled by reading the entry point's stored methods back before sending it again.
         */
        public val retryKey: String? = null,
    ) : PayInSubmissionState() {
        public val fieldErrors: Map<PayInField, PayInFieldError> =
            Collections.unmodifiableMap(fieldErrors.toMap())

        /** The cause renders as its own classification, which carries no server text. */
        override fun toString(): String =
            "PayInSubmissionState.Failed(cause=$cause, fields=${fieldErrors.keys}, hasRetryKey=${retryKey != null})"
    }
}
