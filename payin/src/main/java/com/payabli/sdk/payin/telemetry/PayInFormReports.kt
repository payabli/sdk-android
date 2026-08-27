package com.payabli.sdk.payin.telemetry

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.reason
import com.payabli.sdk.payin.form.telemetryName

/**
 * What the card-not-present form reports, away from what it draws.
 *
 * The two composables call these and hold no vocabulary of their own, as `PayInSubmission` does for the
 * outcome. Where each is called still matters: [reportFormPresented] and [reportFormSubmitted] need the
 * operation, which the drawing half does not have.
 */
internal fun reportFormPresented(step: String) {
    TelemetryRecorders.record(TelemetryEvents.FORM_PRESENTED) {
        mapOf(TelemetryProperty.STEP.key to step)
    }
}

internal fun reportFormSubmitted(step: String) {
    TelemetryRecorders.record(TelemetryEvents.FORM_SUBMITTED) {
        mapOf(TelemetryProperty.STEP.key to step)
    }
}

/**
 * One report per field the service refused.
 *
 * Not from the field boxes: a rule there answers on every keystroke and calls a half-typed number too short.
 */
internal fun reportRefusedFields(refused: Map<PayInField, PayInFieldError>) {
    refused.forEach { (field, rejection) ->
        TelemetryRecorders.record(TelemetryEvents.FORM_VALIDATION_ERROR) {
            mapOf(
                TelemetryProperty.FIELD.key to field.telemetryName,
                TelemetryProperty.REASON.key to rejection.reason,
            )
        }
    }
}
