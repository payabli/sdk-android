package com.payabli.sdk.payin.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The method a form shows and the one the host was last told, which are the form's rather than a flow's: handed
 * another flow, the form keeps the method wherever the new configuration offers it.
 */
internal class PayInFormMethod {
    private var chosen: PayInMethodType? by mutableStateOf(null)

    /** Null until the form first names a method, so a form opens silently. */
    var told: PayInMethodType? = null

    /**
     * The method on screen. Read rather than captured, so the submit button's check at the tap sees what the payer
     * chose rather than what the last composition drew.
     */
    val current: PayInMethodType
        get() = checkNotNull(chosen) { "a form's method was read before the form opened" }

    /**
     * Keeps the method on screen where [configuration] offers it, and otherwise takes its starting method.
     *
     * @param configuration the configuration the form draws.
     */
    fun open(configuration: PayInFormConfiguration) {
        chosen = chosen?.takeIf { it in configuration.methodsOffered } ?: configuration.startingMethod
    }

    /**
     * The payer chose [method].
     *
     * @param method the method switched to.
     */
    fun switchTo(method: PayInMethodType) {
        chosen = method
    }
}
