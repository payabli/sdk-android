package com.payabli.example.app.demo.ui.capture

import com.payabli.example.app.demo.flow.PaymentProgress
import com.payabli.example.app.demo.flow.PaymentSteps
import com.payabli.example.app.demo.flow.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The capture screen's steps while the SDK's flag is up, as the screen builds them. */
class CaptureProgressTest {
    private fun steps(
        isSubmitting: Boolean,
        isVoiding: Boolean,
    ) = PaymentSteps.forCapture(
        PaymentProgress(
            backendReachable = true,
            backendChecked = true,
            isSubmitting = formSubmitting(isSubmitting, isVoiding),
            finished = true,
        ),
    )

    @Test
    fun `a void keeps the transaction it reverses in view`() {
        val steps = steps(isSubmitting = true, isVoiding = true)

        assertEquals("the void moved the form step back", StepStatus.Done, steps[1].status)
        assertEquals("the void took the transaction off the screen", StepStatus.Current, steps[2].status)
    }

    @Test
    fun `a submission of the form still reads as in flight`() {
        val steps = steps(isSubmitting = true, isVoiding = false)

        assertEquals(StepStatus.InProgress, steps[1].status)
    }
}
