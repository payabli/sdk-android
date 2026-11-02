package com.payabli.example.app.sdk

import android.content.Context

/**
 * Whether the SDK supports card-present payments on this device.
 *
 * Always `false`: this variant doesn't link the card reader.
 *
 * @param context Unused, and taken so both variants share one signature.
 * @return `false`.
 */
@Suppress("UNUSED_PARAMETER")
fun cardPresentSupported(context: Context): Boolean = false
