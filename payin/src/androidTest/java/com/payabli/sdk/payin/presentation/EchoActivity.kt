package com.payabli.sdk.payin.presentation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The screen a contract opens, in place of the Google Pay sheet. It counts its launches and answers only when the test
 * says so, so a test can act on the presentation Activity while this one is on top of it.
 */
class EchoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated instance is the same launch, so only a fresh one counts.
        if (savedInstanceState == null) LAUNCHES.incrementAndGet()
        CURRENT.set(this)
    }

    override fun onDestroy() {
        CURRENT.compareAndSet(this, null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VALUE = "value"

        /** Fresh launches since [reset]. */
        val LAUNCHES = AtomicInteger()

        /** The instance on screen, or null. */
        val CURRENT = AtomicReference<EchoActivity?>()

        fun reset() {
            LAUNCHES.set(0)
            CURRENT.set(null)
        }

        /**
         * Finishes the instance on screen with [resultCode], echoing the value it was started with.
         *
         * @param resultCode `RESULT_OK` or `RESULT_CANCELED`.
         */
        fun answer(resultCode: Int) {
            val echo = requireNotNull(CURRENT.get()) { "no EchoActivity is on screen" }
            echo.runOnUiThread {
                val value = echo.intent.getStringExtra(EXTRA_VALUE)
                echo.setResult(resultCode, Intent().putExtra(EXTRA_VALUE, value))
                echo.finish()
            }
        }
    }
}
