package com.payabli.example.app.payment

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

    /** Submit whatever the form collected. Returns a result or an error, never throws. */
    suspend fun submit(): Result<PaymentResult>
}
