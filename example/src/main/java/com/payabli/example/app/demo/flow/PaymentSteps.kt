package com.payabli.example.app.demo.flow

/**
 * How far a card-not-present screen has got.
 *
 * @param backendReachable the token endpoint answered.
 * @param backendChecked the check has run at all, whatever it said.
 * @param isCheckingBackend the check is running now.
 * @param isSubmitting the SDK is submitting.
 * @param submitFailed the last submission failed, or came back carrying nothing.
 * @param finished a result arrived and is on screen.
 */
data class PaymentProgress(
    val backendReachable: Boolean = false,
    val backendChecked: Boolean = false,
    val isCheckingBackend: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitFailed: Boolean = false,
    val finished: Boolean = false,
)

/**
 * What a card-not-present screen asks for, in the order the SDK needs it.
 *
 * Pure, and outside `ui`, so the sequence can be checked without a composition.
 */
object PaymentSteps {
    /** Storing an instrument, which returns a token to reuse. */
    fun forStoringMethod(progress: PaymentProgress): List<FlowStep> =
        forPayment(
            progress = progress,
            resultTitle = "Stored method",
            resultDetail = "A successful submit returns a reusable stored-method id.",
            formTitle = "Enter the card or ACH details",
        )

    /** Taking a payment now. */
    fun forCapture(progress: PaymentProgress): List<FlowStep> =
        forPayment(
            progress = progress,
            resultTitle = "Transaction",
            resultDetail = "A successful submit returns an approved transaction id.",
            formTitle = "Enter the payment details",
        )

    private fun forPayment(
        progress: PaymentProgress,
        resultTitle: String,
        resultDetail: String,
        formTitle: String,
    ): List<FlowStep> {
        val backend =
            when {
                // Before the outcome, or the step offers its button over a request already in flight.
                progress.isCheckingBackend -> StepStatus.InProgress
                progress.backendChecked && !progress.backendReachable -> StepStatus.Failed
                progress.backendReachable -> StepStatus.Done
                else -> StepStatus.Current
            }

        val form =
            when {
                !backend.isFinished -> StepStatus.Blocked
                progress.isSubmitting -> StepStatus.InProgress
                progress.submitFailed -> StepStatus.Failed
                progress.finished -> StepStatus.Done
                else -> StepStatus.Current
            }

        val result =
            when {
                // From the step before, not `finished`, which can be true while the form is blocked.
                form.isFinished -> StepStatus.Current
                else -> StepStatus.Blocked
            }

        return listOf(
            FlowStep(
                title = "Reach the token backend",
                detail = "The SDK asks your backend for a short-lived access token before it submits.",
                status = backend,
            ),
            FlowStep(
                title = formTitle,
                detail = "The SDK owns these fields; clear PAN never reaches the host app.",
                status = form,
            ),
            FlowStep(title = resultTitle, detail = resultDetail, status = result),
        )
    }
}
