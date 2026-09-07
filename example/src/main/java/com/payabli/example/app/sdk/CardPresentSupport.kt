package com.payabli.example.app.sdk

import android.content.Context
import com.payabli.sdk.taptopay.PayabliTTP

/**
 * Whether the SDK supports card-present payments on this device.
 *
 * Here rather than beside the preflight that reads it, because this app keeps every call into the SDK in
 * one package and hands the rest of itself a type it owns.
 *
 * Advisory, and the preflight treats it that way: a `false` is a reason not to offer the flow, and a `true`
 * says nothing about the paypoint, the vendor's view of this device, or a reader that fails once armed.
 */
fun cardPresentSupported(context: Context): Boolean = PayabliTTP.isSupported(context)
