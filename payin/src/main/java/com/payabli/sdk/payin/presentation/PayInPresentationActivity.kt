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
        id = intent.getLongExtra(PayInPresentation.EXTRA_ID, NO_ID)
        val presentation = PayInPresentation.attach(id, this)
        if (presentation == null) {
            // A restored process: the call that opened this died with the old one, so nothing waits for an answer.
            delivered = true
            finish()
            return
        }
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) == true
        presentation.start(this, launch = !launched) { deliver(it) }
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

    private companion object {
        const val NO_ID = -1L
        const val KEY_LAUNCHED = "com.payabli.sdk.payin.presentation.LAUNCHED"
    }
}
