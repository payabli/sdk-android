package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInFormValues

/** What a payment screen is for. */
enum class PaymentOperation {
    /** Store an instrument and get a token. */
    StoreMethod,

    /** Charge an instrument now. */
    Capture,
}

/**
 * The non-UI half of the payment seam.
 *
 * The screens hold one of these and never an SDK type, so when the SDK's payment component arrives
 * the swap is one line in `AppContainer`. [DemoPaymentFlowController] stands here today.
 */
interface PaymentFlowController {
    val operation: PaymentOperation

    val setup: DemoFormSetup

    /**
     * Submit what the form collected. Returns a result or an error, never throws.
     *
     * [values] carries the instrument the payer chose, which is the one thing a host cannot work
     * out from its own configuration.
     */
    suspend fun submit(values: PayInFormValues): Result<PaymentResult>
}
