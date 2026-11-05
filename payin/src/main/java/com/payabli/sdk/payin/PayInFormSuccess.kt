package com.payabli.sdk.payin

import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod

/**
 * The two outcomes a drawn form can reach. Not a state: there is no idle, no in-flight and no
 * failure arm, because [PayabliPayIn.isSubmitting] and the form's `onFailed` carry those.
 */
public sealed class PayInFormSuccess {
    /** A capture, an authorization, or the capture of an authorization. */
    public class Payment(
        public val result: PayInResult,
    ) : PayInFormSuccess() {
        override fun toString(): String = "PayInFormSuccess.Payment(code=${result.code})"
    }

    /** A method the service stored, identified so a later transaction can charge it. */
    public class Method(
        public val storedMethod: PayInStoredMethod,
    ) : PayInFormSuccess() {
        override fun toString(): String = "PayInFormSuccess.Method"
    }
}
