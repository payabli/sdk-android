package com.payabli.example.app.flow

/**
 * What a card-not-present screen asks for, in the order the SDK needs it.
 *
 * Pure, and outside `ui`, so the sequence a reader is shown can be checked without a composition.
 * The two screens differ in what the last step is called and in nothing else, which is the point:
 * storing a method and taking a payment are the same three questions.
 */
object PaymentSteps {
    /**
     * @param backendReachable the token endpoint answered.
     * @param backendChecked the check has run at all, whatever it said.
     * @param isCheckingBackend the check is running now.
     * @param isSubmitting the SDK is submitting.
     * @param submitFailed the last submission failed.
     * @param finished a result came back and is on screen.
     */
    fun forPayment(
        backendReachable: Boolean,
        backendChecked: Boolean,
        isCheckingBackend: Boolean,
        isSubmitting: Boolean,
        submitFailed: Boolean,
        finished: Boolean,
        resultTitle: String,
        resultDetail: String,
    ): List<FlowStep> {
        val backend =
            when {
                // Before the outcome, or the step keeps saying "do this next" and keeps its button
                // while the request it started is still in flight.
                isCheckingBackend -> StepStatus.InProgress
                backendChecked && !backendReachable -> StepStatus.Failed
                backendReachable -> StepStatus.Done
                else -> StepStatus.Current
            }

        val form =
            when {
                // Waits rather than competing with the step above for attention.
                !backend.isFinished -> StepStatus.Blocked
                isSubmitting -> StepStatus.InProgress
                submitFailed -> StepStatus.Failed
                finished -> StepStatus.Done
                else -> StepStatus.Current
            }

        val result =
            when {
                // Read from the step before rather than from `finished`, which can be true while the
                // form is blocked and would put two steps forward at once. A failure also belongs to
                // the step that produced it: marking this one failed as well would leave the
                // sequence with two things to fix and no order between them.
                form.isFinished -> StepStatus.Current
                else -> StepStatus.Blocked
            }

        return listOf(
            FlowStep(
                title = "Reach the token backend",
                detail = "The SDK asks your backend for a short-lived token before it submits anything.",
                status = backend,
            ),
            FlowStep(
                title = "Enter the details",
                detail = "The SDK owns these fields. A card number never reaches this app.",
                status = form,
            ),
            FlowStep(title = resultTitle, detail = resultDetail, status = result),
        )
    }

    /** Storing an instrument, which returns a token to reuse. */
    fun forStoringMethod(
        backendReachable: Boolean,
        backendChecked: Boolean,
        isCheckingBackend: Boolean,
        isSubmitting: Boolean,
        submitFailed: Boolean,
        finished: Boolean,
    ): List<FlowStep> =
        forPayment(
            backendReachable = backendReachable,
            backendChecked = backendChecked,
            isCheckingBackend = isCheckingBackend,
            isSubmitting = isSubmitting,
            submitFailed = submitFailed,
            finished = finished,
            resultTitle = "Stored method",
            resultDetail = "A stored method comes back with an id you can charge later.",
        )

    /** Taking a payment now. */
    fun forCapture(
        backendReachable: Boolean,
        backendChecked: Boolean,
        isCheckingBackend: Boolean,
        isSubmitting: Boolean,
        submitFailed: Boolean,
        finished: Boolean,
    ): List<FlowStep> =
        forPayment(
            backendReachable = backendReachable,
            backendChecked = backendChecked,
            isCheckingBackend = isCheckingBackend,
            isSubmitting = isSubmitting,
            submitFailed = submitFailed,
            finished = finished,
            resultTitle = "Transaction",
            resultDetail = "A captured payment comes back with a transaction to show.",
        )
}
