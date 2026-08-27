package com.payabli.sdk.payin.telemetry

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
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
 *
 * **[session] is the flow's, not whichever is installed when the effect runs.** A form outlives a
 * re-initialize, so a form built under a session that had reporting off would otherwise report through the
 * successor that has it on.
 */
internal fun reportFormPresented(
    session: TelemetrySessionContext?,
    step: String,
) {
    record(session, TelemetryEvents.FORM_PRESENTED) {
        mapOf(TelemetryProperty.STEP.key to step)
    }
}

internal fun reportFormSubmitted(
    session: TelemetrySessionContext?,
    step: String,
) {
    record(session, TelemetryEvents.FORM_SUBMITTED) {
        mapOf(TelemetryProperty.STEP.key to step)
    }
}

/** Null where no session built the flow, which is a test driving a transport directly. */
private inline fun record(
    session: TelemetrySessionContext?,
    event: String,
    properties: () -> Map<String, String>,
) {
    if (session != null) {
        TelemetryRecorders.recordFor(session, event, properties)
    } else {
        TelemetryRecorders.record(event, properties)
    }
}

/**
 * One report per field the service refused.
 *
 * Not from the field boxes: a rule there answers on every keystroke and calls a half-typed number too short.
 */
internal fun reportRefusedFields(
    session: TelemetrySessionContext?,
    refused: Map<PayInField, PayInFieldError>,
) {
    refused.forEach { (field, rejection) ->
        record(session, TelemetryEvents.FORM_VALIDATION_ERROR) {
            mapOf(
                TelemetryProperty.FIELD.key to field.telemetryName,
                TelemetryProperty.REASON.key to rejection.reason,
            )
        }
    }
}
