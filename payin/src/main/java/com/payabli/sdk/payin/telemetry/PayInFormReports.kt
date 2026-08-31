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
 * Built once, by the flow, from the session that created it, and handed down. The composables call methods
 * on it and hold no telemetry type of their own: a form that outlives a re-initialize still reports under
 * the session it belongs to, and the drawing half does not have to know that is a question.
 */
internal class PayInFormReports(
    private val session: TelemetrySessionContext?,
) {
    /** The form appeared. [step] is the operation it was drawn for. */
    fun presented(step: String) {
        record(TelemetryEvents.FORM_PRESENTED) {
            mapOf(TelemetryProperty.STEP.key to step)
        }
    }

    /** The payer submitted it, before anything is sent. */
    fun submitted(step: String) {
        record(TelemetryEvents.FORM_SUBMITTED) {
            mapOf(TelemetryProperty.STEP.key to step)
        }
    }

    /**
     * One report per field the service refused.
     *
     * Not from the field boxes: a rule there answers on every keystroke and calls a half-typed number too
     * short.
     */
    fun refusedFields(refused: Map<PayInField, PayInFieldError>) {
        refused.forEach { (field, rejection) ->
            record(TelemetryEvents.FORM_VALIDATION_ERROR) {
                mapOf(
                    TelemetryProperty.FIELD.key to field.telemetryName,
                    TelemetryProperty.REASON.key to rejection.reason,
                )
            }
        }
    }

    private inline fun record(
        event: String,
        properties: () -> Map<String, String>,
    ) {
        if (session != null) {
            TelemetryRecorders.recordFor(session, event, properties)
        } else {
            TelemetryRecorders.record(event, properties)
        }
    }

    internal companion object {
        val None: PayInFormReports = PayInFormReports(null)
    }
}
