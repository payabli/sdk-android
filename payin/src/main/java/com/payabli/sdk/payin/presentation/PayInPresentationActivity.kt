package com.payabli.sdk.payin.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The SDK's own transparent Activity, which launches the contract [PayInPresentation] holds for it and hands the answer
 * back. "Launched" is kept in saved state, so a recreated instance waits for the answer instead of launching again.
 */
internal class PayInPresentationActivity : ComponentActivity() {
    private var id = NO_ID
    private var launched = false
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = PayInPresentation.idOf(intent) ?: NO_ID
        val presentation = PayInPresentation.attach(id, this)
        if (presentation == null) {
            // A restored process: the call that opened this died with the old one, so nothing waits for an answer.
            delivered = true
            finish()
            return
        }
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) == true
        try {
            presentation.start(this, launch = !launched, onOutcome = ::deliver, onFailure = ::fail)
        } catch (e: Exception) {
            // Thrown out of onCreate it would end the process. The call that asked for the launch receives it instead.
            fail(e)
            return
        }
        launched = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
    }

    override fun onDestroy() {
        // Finishing without an answer is a cancel: back pressed, or the system closed it.
        if (isFinishing && !delivered) PayInPresentation.deliver(id, PayInPresentationOutcome.Cancelled)
        PayInPresentation.detach(id, this)
        super.onDestroy()
    }

    private fun deliver(outcome: PayInPresentationOutcome<*>) {
        delivered = true
        PayInPresentation.deliver(id, outcome)
        finish()
    }

    private fun fail(error: Exception) {
        delivered = true
        PayInPresentation.fail(id, error)
        finish()
    }

    private companion object {
        const val NO_ID = -1L
        const val KEY_LAUNCHED = "com.payabli.sdk.payin.presentation.LAUNCHED"
    }
}
